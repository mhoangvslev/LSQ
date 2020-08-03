package org.aksw.simba.lsq.core;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.aksw.jena_sparql_api.core.utils.UpdateRequestUtils;
import org.aksw.jena_sparql_api.mapper.hashid.HashIdCxt;
import org.aksw.jena_sparql_api.mapper.proxy.MapperProxyUtils;
import org.aksw.jena_sparql_api.rx.DatasetGraphOpsRx;
import org.aksw.jena_sparql_api.rx.RDFDataMgrRx;
import org.aksw.jena_sparql_api.rx.SparqlRx;
import org.aksw.jena_sparql_api.rx.query_flow.QueryFlowOps;
import org.aksw.jena_sparql_api.utils.ElementUtils;
import org.aksw.jena_sparql_api.utils.ExprUtils;
import org.aksw.jena_sparql_api.utils.Quads;
import org.aksw.jena_sparql_api.utils.ResultSetUtils;
import org.aksw.jena_sparql_api.utils.Vars;
import org.aksw.simba.lsq.model.ExperimentConfig;
import org.aksw.simba.lsq.model.ExperimentRun;
import org.aksw.simba.lsq.model.LocalExecution;
import org.aksw.simba.lsq.model.LsqQuery;
import org.aksw.simba.lsq.model.QueryExec;
import org.aksw.simba.lsq.spinx.model.LsqTriplePattern;
import org.aksw.simba.lsq.spinx.model.SpinBgp;
import org.aksw.simba.lsq.spinx.model.SpinBgpNode;
import org.aksw.simba.lsq.spinx.model.SpinQueryEx;
import org.aksw.simba.lsq.vocab.LSQ;
import org.apache.jena.datatypes.xsd.XSDDateTime;
import org.apache.jena.ext.com.google.common.base.Stopwatch;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionFactory;
import org.apache.jena.rdfconnection.SparqlQueryConnection;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.modify.request.QuadAcc;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.Template;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.update.UpdateRequest;
import org.apache.jena.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.topbraid.spin.model.TriplePattern;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableTransformer;


/**
 * This is the LSQ2 processor for benchmarking RDFized query log
 *
 * @author raven
 *
 */
public class LsqBenchmarkProcessor {
    static final Logger logger = LoggerFactory.getLogger(LsqBenchmarkProcessor.class);


    public static FlowableTransformer<LsqQuery, LsqQuery> createProcessor() {
        return null;
    }


    public static void run() {
        SparqlQueryConnection benchmarkConn = RDFConnectionFactory.connect(DatasetFactory.create());

        Model configModel = ModelFactory.createDefaultModel();

        String expId = "testrun";
        String expSuffix = "_" + expId;
        String lsqBaseIri = "http://lsq.aksw.org/";

        ExperimentConfig config = configModel
                .createResource("http://someconfig.at/now")
                .as(ExperimentConfig.class)
                .setIdentifier(expId);

        Instant benchmarkRunStartTimestamp = Instant.ofEpochMilli(0);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(benchmarkRunStartTimestamp, ZoneId.systemDefault());
        Calendar cal = GregorianCalendar.from(zdt);
        XSDDateTime xsddt = new XSDDateTime(cal);

        ExperimentRun expRun = configModel
                .createResource()
                .as(ExperimentRun.class)
                .setConfig(config)
                .setTimestamp(xsddt);

        HashIdCxt tmp = MapperProxyUtils.getHashId(expRun);
        String expRunIri = lsqBaseIri + tmp.getString(expRun);
        expRun = ResourceUtils.renameResource(expRun, expRunIri).as(ExperimentRun.class);

        Flowable<LsqQuery> queryFlow = RDFDataMgrRx.createFlowableResources("../tmp/2020-06-27-wikidata-one-day.trig", Lang.TRIG, null)
                .map(r -> r.as(LsqQuery.class));

        process(queryFlow, lsqBaseIri, config, expRun, benchmarkConn);
    }

    /**
     *
     * @param lsqBaseIri The IRI prefix to prepend to generated resources
     * @param expSuffix A suffix to be appended to the automatically derived query hashes
     *        in order to generate the full id used for database lookup (and possibly update)
     *        of benchmark information
     * @param benchmarkConn The connection on which to perform benchmarking
     */
    public static void process(
            Flowable<LsqQuery> rawQueryFlow,
            String lsqBaseIri,
            ExperimentConfig config,
            ExperimentRun expRun,
            SparqlQueryConnection benchmarkConn) {


        //ExperimentConfig config = expRun.getConfig();
        //String expSuffix


        String datasetLabel = config.getDatasetLabel();
        XSDDateTime benchmarkRunTimestamp = expRun.getTimestamp();
        Instant instant = benchmarkRunTimestamp.asCalendar().toInstant();
        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        String benchmarkRunTimestampStr = DateTimeFormatter.ISO_LOCAL_DATE.format(zdt);
        String expId = datasetLabel + "_" + benchmarkRunTimestampStr;
        String expSuffix = "_" + expId;


        Function<String, String> lsqQueryBaseIriFn = hash -> lsqBaseIri + "q-" + hash;

        // Function<LsqQuery, String> lsqQueryExecFn = lsqQuery -> lsqQueryBaseIriFn.apply(lsqQuery.getHash()) + expSuffix;

//        Function<LsqQuery, String> lsqQueryExecFn = lsqQuery -> "urn://" + lsqQuery.getHash() + expSuffix;
        Function<LsqQuery, String> lsqQueryExecFn = lsqQuery -> lsqQueryBaseIriFn.apply(lsqQuery.getHash()) + expSuffix;

//        Flowable<List<Set<LsqQuery>>> queryFlow = RDFDataMgrRx.createFlowableResources("../tmp/2020-06-27-wikidata-one-day.trig", Lang.TRIG, null)
//                Flowable<List<Set<LsqQuery>>> queryFlow = RDFDataMgrRx.createFlowableResources("../tmp/saleem.trig", Lang.TRIG, null)
        Flowable<List<Set<LsqQuery>>> queryFlow = rawQueryFlow
//                .map(r -> r.as(LsqQuery.class))
//                .skip(1)
//                .take(1)
                .flatMapMaybe(lsqQuery -> LsqEnrichments.enrichWithFullSpinModel(lsqQuery), false, 1)
//                .concatMapMaybe(lsqQuery -> enrichWithFullSpinModel(lsqQuery))
                .map(anonQuery -> updateLsqQueryIris(anonQuery, q -> lsqQueryBaseIriFn.apply(q.getHash())))
                .map(lsqQuery -> LsqEnrichments.enrichWithStaticAnalysis(lsqQuery))
//                .doAfterNext(x -> {
//                    RDFDataMgr.write(System.out, x.getModel(), RDFFormat.TURTLE_FLAT);
//                    System.exit(1);
//                })
                //.flatMap(lsqQuery -> Flowable.fromIterable(extractAllQueries(lsqQuery)), false, 128)
                .map(lsqQuery -> extractAllQueries(lsqQuery))
//                .doAfterNext(lsqQuery -> lsqQuery.updateHash())
//                .doOnNext(r -> ResourceUtils.renameResource(r, "http://lsq.aksw.org/q-" + r.getHash()).as(LsqQuery.class))
//                .lift(OperatorObserveThroughput.create("throughput", 100))
                .buffer(1)
//                .lift(OperatorObserveThroughput.create("buffered", 100))
                ;

        Iterable<List<Set<LsqQuery>>> batches = queryFlow.blockingIterable();
        Iterator<List<Set<LsqQuery>>> itBatches = batches.iterator();

        // Create a database to ensure uniqueness of evaluation tasks
        Dataset dataset = TDB2Factory.connectDataset("/tmp/lsq-benchmark-index");
        try(RDFConnection indexConn = RDFConnectionFactory.connect(dataset)) {

            while(itBatches.hasNext()) {
                List<Set<LsqQuery>> batch = itBatches.next();
                processBatchOfQueries(
                        batch,
                        lsqBaseIri,
                        config,
                        expRun,
                        benchmarkConn,
                        lsqQueryExecFn,
                        indexConn);
            }
        } finally {
            dataset.close();

        }


//
//        Model model = ModelFactory.createDefaultModel();
//        SpinQueryEx spinRes = model.createResource("http://test.ur/i").as(SpinQueryEx.class);
//
//
//
//        // Create a stream of tasks which to benchmark:
//        // Create a stream of bgps
//        // Create a stream of tps
//        // create a stream of sub-bgps
//
//        // Then for each of these resources,
//
//
//
////        RDFDataMgr.write(System.out, model, RDFFormat.TURTLE_PRETTY);
//
//        for(SpinBgp bgp : spinRes.getBgps()) {
//            System.out.println(bgp);
//            Collection<TriplePattern> tps = bgp.getTriplePatterns();
//
//            for(TriplePattern tp : tps) {
//                System.out.println(tp);
//            }
//        }
//
//        QueryStatistics2.enrichSpinQueryWithBgpStats(spinRes);
//        // QueryStatistics2.setUpJoinVertices(spinRes);
//        QueryStatistics2.getDirectQueryRelatedRDFizedStats(spinRes, spinRes);
//
//        // TODO Make skolemize reuse skolem ID resources
//        Skolemize.skolemize(spinRes);
//
//
//
//        // TODO How to perform triple pattern and join evaluation?
//        // Actually we would need to create a stream of unique Triple Patterns and BGPs
//        // So should we use an (embedded DB) to keep track of for which items the statistics have already been computed
//        // within a benchmark experiment?
//
//
//
////        QueryStatistics2.fetchCountJoinVarElement(qef, itemToElement)
//
//        // Now to create the evaluation results...
//        // LsqProcessor.rdfizeQueryExecutionStats
////        SpinUtils.enrichModelWithTriplePatternExtensionSizes(queryRes, queryExecRes, cachedQef);
//
//
//
//        //Skolemize.skolemize(spinRes);
//
//        RDFDataMgr.write(System.out, model, RDFFormat.TURTLE_PRETTY);
    }


    public static void processBatchOfQueries(
            List<Set<LsqQuery>> batch,
            String lsqBaseIri,
            ExperimentConfig config,
            ExperimentRun expRun,
            SparqlQueryConnection benchmarkConn,
            Function<LsqQuery, String> lsqQueryExecFn,
            RDFConnection indexConn) {

        Set<Node> lookupHashNodes = batch.stream()
                .flatMap(item -> item.stream())
                .map(lsqQueryExecFn)
                .map(NodeFactory::createURI)
                .collect(Collectors.toSet());

//                for(Node n : lookupHashNodes) {
//                    System.out.println("Lookup with: " + n.getURI());
//                }
//                Expr filter = ExprUtils.oneOf(Vars.g, lookupHashNodes);
//                dq.filterDirect(new ElementFilter(filter));

        Map<String, Dataset> nodeToDataset = Txn.calculate(indexConn, () ->
            fetchDatasets(indexConn, lookupHashNodes)
            .toMap(Entry::getKey, Entry::getValue)
            .blockingGet());

//                System.out.println(hashNodes);
        // Obtain the set of query strings already in the store

        Set<String> alreadyIndexedHashUrns = nodeToDataset.keySet();
//                Set<String> alreadyIndexedHashUrns = Txn.calculate(indexConn, () ->
//                      dq
//                        .exec()
//                        .map(x -> x.asNode().getURI())
//                        .blockingStream()
//                        .collect(Collectors.toSet()));
//                        ;

//                for(String str : alreadyIndexedHashUrns) {
//                    System.out.println("Already indexed: " + str);
//                }
        Set<LsqQuery> nonIndexed = batch.stream()
            .flatMap(item -> item.stream())
            .filter(item -> !alreadyIndexedHashUrns.contains(lsqQueryExecFn.apply(item)))
            .collect(Collectors.toSet());

//                System.out.println(nonIndexed);

        List<Quad> inserts = new ArrayList<Quad>();

//                System.err.println("Batch: " + nonIndexed.size() + "/" + lookupHashNodes.size() + " (" + batch.size() + ") need processing");
        for(LsqQuery item : nonIndexed) {
            String queryStr = item.getText();
            String queryExecIri = lsqQueryExecFn.apply(item); //"urn://" + item.getHash();
            Node s = NodeFactory.createURI(queryExecIri);
//                    System.out.println("Benchmarking: " + s);
//                    System.out.println(item.getText());

            Dataset newDataset = DatasetFactory.create();
            Model newModel = newDataset.getNamedModel(queryExecIri);

            item = item.inModel(newModel).as(LsqQuery.class);

            // Create fresh local execution and query exec resources
            LocalExecution le = newModel.createResource().as(LocalExecution.class);
            QueryExec qe = newModel.createResource().as(QueryExec.class);

            // TODO Skolemize these executions!

            rdfizeQueryExecutionBenchmark(
                    benchmarkConn,
                    queryStr,
                    qe,
                    config.getQueryConnectionTimeout(),
                    config.getQueryExecutionTimeout(),
                    config.getMaxItemCountForCounting(),
                    config.getMaxByteSizeForCounting(),
                    config.getMaxItemCountForSerialization(),
                    config.getMaxByteSizeForSerialization());

            item.getLocalExecutions().add(le);
            le.setBenchmarkRun(expRun);
            le.setQueryExec(qe);

            newDataset.asDatasetGraph().find().forEachRemaining(inserts::add);


            inserts.add(new Quad(s, s, LSQ.execStatus.asNode(), NodeFactory.createLiteral("processed")));
//                    RDFDataMgr.write(new FileOutputStream(FileDescriptor.out), item.getModel(), RDFFormat.TURTLE_PRETTY);

//                    System.out.println(s.getURI().equals("urn://33c4205f90fdeb7d1db68426806a816e3ffbfa3980461b556b32fded407568c9"));

            nodeToDataset.put(queryExecIri, newDataset);
        }

        UpdateRequest ur = UpdateRequestUtils.createUpdateRequest(inserts, null);
        Txn.executeWrite(indexConn, () -> indexConn.update(ur));

//                Txn.executeRead(indexConn, () -> System.out.println(ResultSetFormatter.asText(indexConn.query("SELECT ?s { ?s ?p ?o }").execSelect())));

        for(Set<LsqQuery> pack : batch) {

            logger.info("Processing pack of size: " + pack.size());

            // The master query is assumed to always be the first element of a pack
            LsqQuery masterQuery = pack.iterator().next();


            Model model = ModelFactory.createDefaultModel();


            // We need to add the config model in order to include the benchmark run id
            // TODO We should ensure that only the minimal necessary config model is added
            Model configModel = config.getModel();
//                    expRoot.getModel().add(configModel);
            model.add(configModel);


            //Model model = rootQuery.getModel();

            // Extend the rootQuery's model with all related query executions
            for(LsqQuery item : pack) {
                String key = lsqQueryExecFn.apply(item);
                Dataset ds = nodeToDataset.get(key);
                Objects.requireNonNull(ds, "Expected dataset for key "  + key);
                Model m = ds.getNamedModel(key);
                Objects.requireNonNull(m, "Should not happen: No query execution model for " + key);

//                        System.err.println("BEGIN***********************************************");
//                        if(item.getModel().contains(ResourceFactory.createResource("http://www.bigdata.com/rdf#serviceParam"), null, (RDFNode)null)) {
//                            System.out.println("here");
//                        }
//                        RDFDataMgr.write(System.err, item.getModel(), RDFFormat.TURTLE_PRETTY);
//                        System.err.println("END***********************************************");

                // Adding the master query's model to itself should be harmless
                model.add(m);
                model.add(item.getModel());
            }


            masterQuery = masterQuery.inModel(model).as(LsqQuery.class);

            SpinQueryEx spinRoot = masterQuery.getSpinQuery().as(SpinQueryEx.class);

            // Update triple pattern selectivities
//                    LocalExecution expRoot = model.createResource().as(LocalExecution.class);
            Map<Resource, LocalExecution> rleMap = masterQuery.getLocalExecutionMap();
            LocalExecution expRoot = rleMap.get(expRun);

//                    expRoot.setBenchmarkRun(expRun);

            LsqExec.createAllExecs(masterQuery, expRun);



            HashIdCxt hashIdCxt = MapperProxyUtils.getHashId(expRoot);//.getHash(bgp);
            //Map<RDFNode, HashCode> renames = hashIdCxt.getMapping();
            Map<RDFNode, String> renames = hashIdCxt.getStringMapping();

//                    Map<Resource, String> renames = new LinkedHashMap<>();
//                    for(SpinBgp bgp : spinRoot.getBgps()) {
//                        HashIdCxt hashIdCxt = MapperProxyUtils.getHashId(bgp);//.getHash(bgp);
//
//                        for(Entry<RDFNode, HashCode> e : hashIdCxt.getMapping().entrySet()) {
//                            if(e.getKey().isResource()) {
//                                renames.put(e.getKey().asResource(), e.getValue().toString());
//                            }
//                        }
//                    }

//                    for(Entry<RDFNode, HashCode> e : renames.entrySet()) {
            renameResources(lsqBaseIri, renames);




            RDFDataMgr.write(System.out, spinRoot.getModel(), RDFFormat.TURTLE_BLOCKS);
        }
    }






    public static void renameResources(String lsqBaseIri, Map<RDFNode, String> renames) {
        for(Entry<RDFNode, String> e : renames.entrySet()) {
//                        HashCode hashCode = e.getValue();
//                        String part = BaseEncoding.base64Url().omitPadding().encode(hashCode.asBytes());
            String part = e.getValue();

            String iri = lsqBaseIri + part;
            RDFNode n = e.getKey();
            if(n.isResource()) {
//                            System.out.println("--- RENAME: ");
//                            System.out.println(iri);
//                            System.out.println(n);
//                            System.out.println("------------------------");
//
                ResourceUtils.renameResource(n.asResource(), iri);
            }
        }
    }

    public static LsqQuery updateLsqQueryIris(
            LsqQuery start,
            Function<? super LsqQuery, String> genIri)
    {
        LsqQuery result = renameResources(
                start,
                LsqQuery.class,
                r -> r.getModel().listResourcesWithProperty(LSQ.text),
                r -> genIri.apply(r)
                );
        return result;
    }

    /**
     * Extract all queries associated with elements of the lsq query's spin representation
     *
     * @param masterQuery
     * @return
     */
    public static Set<LsqQuery> extractAllQueries(LsqQuery masterQuery) {
        Set<LsqQuery> result = new LinkedHashSet<>();

        // Add self by default
        result.add(masterQuery);

        SpinQueryEx spinNode = masterQuery.getSpinQuery().as(SpinQueryEx.class);


        for(SpinBgp bgp : spinNode.getBgps()) {
            extractAllQueriesFromBgp(result, bgp);
        }

        return result;
    }


    public static void extractAllQueriesFromBgp(Set<LsqQuery> result, SpinBgp bgp) {
        LsqQuery extensionQuery = bgp.getExtensionQuery();
        if(extensionQuery != null) {
            result.add(extensionQuery);
        }

        Map<Node, SpinBgpNode> bgpNodeMap = bgp.indexBgpNodes();

        for(SpinBgpNode bgpNode : bgpNodeMap.values()) {
            extensionQuery = bgpNode.getJoinExtensionQuery();
            if(extensionQuery != null) {
                result.add(extensionQuery);
            }

            SpinBgp subBgp = bgpNode.getSubBgp();

            if(subBgp != null) {
                extensionQuery = subBgp.getExtensionQuery();
                if(extensionQuery != null) {
                    result.add(extensionQuery);
                }
            }

            // Get extension queries from triple patterns
            for(TriplePattern tp : bgp.getTriplePatterns()) {
                LsqTriplePattern ltp = tp.as(LsqTriplePattern.class);

                extensionQuery = ltp.getExtensionQuery();
                if(extensionQuery != null) {
                    result.add(extensionQuery);
                }
            }
        }

        // Extract queries from subBgp
        for(SpinBgpNode bgpNode : bgp.getBgpNodes()) {
            SpinBgp subBgp = bgpNode.getSubBgp();

            // The sub-bgp of a variable in a bgp with a single triple pattern is the original bgp;
            // prevent infinite recursion
            if(!subBgp.equals(bgp)) {
                extractAllQueriesFromBgp(result, subBgp);
            }
        }
    }

//    public SparqlQueryConnection configureConnection(SparqlQueryConnection rawConn, ExperimentConfig config) {
//        ExperimentConfig config;
//        BigDecimal connectionTimeout = config.getConnectionTimeout();
//        BigDecimal queryExecutionTimeout = config.getQueryTimeout();
//        Long effectiveConnectionTimeout = connectionTimeout == null
//                ? -1l
//                : connectionTimeout.divide(new BigDecimal(1000)).longValue();
//
//        Long effectiveQueryExecutionTimeout = queryExecutionTimeout == null
//                ? -1l
//                : queryExecutionTimeout.divide(new BigDecimal(1000)).longValue();
//
//        qe.setTimeout(effectiveConnectionTimeout, effectiveQueryExecutionTimeout);
//
//        return null;
//    }

    /*
        * Benchmark the combined execution and retrieval time of a given query
        *
        * @param query
        * @param queryExecRes
        * @param qef
        */
       public static QueryExec rdfizeQueryExecutionBenchmark(
               SparqlQueryConnection conn,
               String queryStr,
               QueryExec result,
               BigDecimal rawConnectionTimeout,
               BigDecimal rawExecutionTimeout,
               Long rawMaxItemCountForCounting,
               Long rawMaxByteSizeForCounting,
               Long rawMaxItemCountForSerialization,
               Long rawMaxByteSizeForSerialization) {



           long connectionTimeout = Optional.ofNullable(rawConnectionTimeout).orElse(new BigDecimal(-1)).divide(new BigDecimal(1000)).longValue();
           long executionTimeout = Optional.ofNullable(rawExecutionTimeout).orElse(new BigDecimal(-1)).divide(new BigDecimal(1000)).longValue();

           long maxItemCountForCounting = Optional.ofNullable(rawMaxItemCountForCounting).orElse(-1l);
           long maxByteSizeForCounting = Optional.ofNullable(rawMaxByteSizeForCounting).orElse(-1l);

           long maxItemCountForSerialization = Optional.ofNullable(rawMaxItemCountForSerialization).orElse(-1l);
           long maxByteSizeForSerialization = Optional.ofNullable(rawMaxByteSizeForSerialization).orElse(-1l);

           boolean exceededMaxItemCountForSerialization = false;
           boolean exceededMaxByteSizeForSerialization = false;

           boolean exceededMaxItemCountForCounting = false;
           boolean exceededMaxByteSizeForCounting = false;


           Stopwatch sw = Stopwatch.createStarted();
           logger.info("Benchmarking " + queryStr);

           List<String> varNames = new ArrayList<>();
           try(QueryExecution qe = conn.query(queryStr)) {
               qe.setTimeout(connectionTimeout, executionTimeout);

               ResultSet rs = qe.execSelect();
               varNames.addAll(rs.getResultVars());

               long estimatedByteSize = 0;
               List<Binding> cache = new ArrayList<>();

               long itemCount = 0; // We could use rs.getRowNumber() but let's not rely on it
               while(rs.hasNext()) {
                   ++itemCount;

                   Binding binding = rs.nextBinding();

                   if(cache != null) {
                       // Estimate the size of the binding (e.g. I once had polygons in literals of size 50MB)
                       long bindingSizeContrib = binding.toString().length();
                       estimatedByteSize += bindingSizeContrib;

                       exceededMaxItemCountForSerialization = maxItemCountForSerialization >= 0
                               && itemCount > maxItemCountForSerialization;

                       if(exceededMaxItemCountForSerialization) {
                           // Disable serialization but keep on counting
                           cache = null;
                       }

                       exceededMaxByteSizeForSerialization = maxByteSizeForSerialization >= 0
                               && estimatedByteSize > maxByteSizeForSerialization;
                       if(exceededMaxByteSizeForSerialization) {
                           // Disable serialization but keep on counting
                           cache = null;
                       }


                       if(cache != null) {
                           cache.add(binding);
                       }
                   }

                   exceededMaxItemCountForCounting = maxItemCountForCounting >= 0
                           && itemCount > maxItemCountForCounting;
                   if(exceededMaxByteSizeForSerialization) {
                       break;
                   }

                   exceededMaxByteSizeForCounting = maxByteSizeForCounting >= 0
                           && estimatedByteSize > maxByteSizeForCounting;
                   if(exceededMaxByteSizeForSerialization) {
                       break;
                   }
               }

               if(exceededMaxItemCountForSerialization) {
                   result.setExceededMaxItemCountForSerialization(exceededMaxItemCountForSerialization);
               }

               if(exceededMaxByteSizeForSerialization) {
                   result.setExceededMaxByteSizeForSerialization(exceededMaxByteSizeForSerialization);
               }


               if(exceededMaxItemCountForCounting) {
                   result.setExceededMaxItemCountForCounting(exceededMaxItemCountForCounting);
               }

               if(exceededMaxByteSizeForCounting) {
                   result.setExceededMaxByteSizeForCounting(exceededMaxByteSizeForCounting);
               }

               if(!exceededMaxItemCountForCounting && !exceededMaxByteSizeForCounting) {
                   result.setResultSetSize(itemCount);
               }

               ByteArrayOutputStream baos = new ByteArrayOutputStream();
               ResultSet replay = ResultSetUtils.create(varNames, cache.iterator());
               ResultSetFormatter.outputAsJSON(baos, replay);
               result.setSerializedResult(baos.toString());

           } catch(Exception e) {
               String errorMsg = e.toString();
               result.setProcessingError(errorMsg);
               logger.warn("Benchmark failure: ", e);
           }

           BigDecimal durationInMillis = new BigDecimal(sw.stop().elapsed(TimeUnit.NANOSECONDS))
                   .divide(new BigDecimal(1000000));

           result
               .setRuntimeInMs(durationInMillis)
           ;

           logger.info("Benchmark result after " + durationInMillis + " ms: " + result.getResultSetSize() + " " + result.getProcessingError());

           return result;
           //Calendar end = Calendar.getInstance();
           //Duration duration = Duration.between(start.toInstant(), end.toInstant());
       }

    public static Flowable<Entry<String, Dataset>> fetchDatasets(SparqlQueryConnection conn, Iterable<Node> graphNames) {
        Query query = createFetchNamedGraphQuery(graphNames);
        QuadAcc quadAcc = new QuadAcc();
        quadAcc.addQuad(Quads.gspo);
        Template template = new Template(quadAcc);
        //template.getQuads().add(gspo);

        return SparqlRx.execSelectRaw(() -> conn.query(query))
            .concatMap(QueryFlowOps.createMapperQuads(template)::apply)
            .compose(DatasetGraphOpsRx.groupConsecutiveQuadsRaw(Quad::getGraph, DatasetGraphFactory::create))
            .map(e -> new SimpleEntry<>(e.getKey().getURI(), DatasetFactory.wrap(e.getValue())));
    }

    /**
     * Update all matching resources in a model
     *
     * @param start
     * @param resAndHashToIri
     * @return
     */
    public static <T extends Resource> T renameResources(
            T start,
            Class<T> clazz,
            Function<? super Resource, ? extends Iterator<? extends RDFNode>> listResources,
            Function<? super T, String> resAndHashToIri) {
        // Rename the query resources - Done outside of this method
        T result = start;
        //Set<Resource> qs = model.listResourcesWithProperty(LSQ.text).toSet();

        Iterator<? extends RDFNode> it = listResources.apply(start);
        while(it.hasNext()) {
            RDFNode tmpQ = it.next();
            T q = tmpQ.as(clazz);
            String iri = resAndHashToIri.apply(q);

            Resource newRes = ResourceUtils.renameResource(q, iri);
            if(q.equals(start)) {
                result = newRes.as(clazz);
            }
        }

        return result;
    }

    /**
     * SELECT * {
     *   GRAPH ?g { ?s ?p ?o }
     *   FILTER(?g IN (args))
     * }
     *
     */
    public static Query createFetchNamedGraphQuery(Iterable<Node> graphNames) {
        Query result = new Query();
        result.setQuerySelectType();
        result.setQueryResultStar(true);
        result.setQueryPattern(
                ElementUtils.groupIfNeeded(
                        ElementUtils.createElement(Quads.gspo),
                        new ElementFilter(ExprUtils.oneOf(Vars.g, graphNames))
                ));
        result.addOrderBy(Vars.g, Query.ORDER_ASCENDING);

        return result;
    }



}





/*
 * TpInBgp
 *
 * CONSTRUCT { ?s lsq:tpSel ?o } {
 *   ?root lsq:hasBgp [ lsq:hasTpInBgp [ lsq:hasTP [ lsq:hasTPExec ?s ] ] ]
 *   ?s lsq:benchmarkRun ?env_run ; lsq:resultSetSize ?rs
 *   ?env_run lsq:datasetSize ?datasetSize
 *   BIND(?rs / ?datasetSize AS ?o)
 * }
 */

//Long datasetSize = config.getDatasetSize();
//if(datasetSize != null) {
//    //for(SpinBgp xbgp : spinRoot.getBgps()) {
//    for(SpinBgpExec bgpExec : expRoot.getBgpExecs()) {
//        SpinBgp xbgp = bgpExec.getBgp();
//        for(TpInBgp tpInBgp : xbgp.getTpInBgp()) {
//            LsqTriplePattern tp = tpInBgp.getTriplePattern();
//
//            LsqQuery extensionQuery = tp.getExtensionQuery();
//            Map<Resource, LocalExecution> leMap = extensionQuery.getLocalExecutionMap();
//            LocalExecution le = leMap.get(expRun);
//            if(le == null) {
//                logger.warn("Missing local execution result");
//            } else {
//                Set<TpInBgpExec> tpInBgpExecs = bgpExec.getTpInBgpExecs();
///*                                    if(tpInBgpExec == null) {
//                    Long rsSize = le.getQueryExec().getResultSetSize();
//                    BigDecimal value = new BigDecimal(rsSize).divide(new BigDecimal(datasetSize));
////                    le.addLiteral(LSQ.tpSel, value);
//
//                    tpInBgpExec = model.createResource(tpInBgpExec.class);
//                    tpInBgpExec
//                        .setTpInBgp(tpInBgp)
//                        .setBgpExec(bgpExec)
//                        .setTpExec()
//                        .setSelectivity(value)
//                        ;
//                }
//                */
//
//
//            }
//        }
//    }
//}


/*
 * BGP restricted tp sel
 * CONSTRUCT { ?s lsq:tpSel ?o } {
 *   ?root lsq:hasBgps [ lsq:hasTP [ lsq:hasTPExec ?s ] ]
 *   ?s lsq:benchmarkRun ?env_run ; lsq:resultSetSize ?rs
 *   ?env_run lsq:datasetSize ?datasetSize
 *   BIND(?rs / ?datasetSize AS ?o)
 * }
 */
//{
//    for(SpinBgp bgp : spinRoot.getBgps()) {
//        for(TpInBgp tpInBgp : bgp.getTpInBgp()) {
//    for(SpinBgpExec bgpExec : expRoot.getBgpExecs()) {
//        for(TpInBgpExec tpInBgpExec : bgpExec.getTpInBgpExecs()) {
//    for(SpinBgpNode bgpNode : spinRoot.getBgps()) {
//        //for(TpInBgp tpInBgpExec : expRoot.getTpInBgpExec()) {
//            LsqTriplePattern tp = tpInBgp.getTriplePattern();
//
//            LocalExecution bgpLe = bgp.getExtensionQuery().getLocalExecutionMap().get(expRun);
//            LocalExecution tpLe = tp.getExtensionQuery().getLocalExecutionMap().get(expRun);
//
//            Long bgpSize = bgpLe.getQueryExec().getResultSetSize();
//            Long tpSize = tpLe.getQueryExec().getResultSetSize();
//
//            BigDecimal bgpRestrictedTpSel = bgpSize == 0 ? new BigDecimal(0) : new BigDecimal(tpSize).divide(new BigDecimal(bgpSize));
//
////                            LocalExecution le = tp.getExtensionQuery().indexLocalExecs().get(expRun);
//
//            TpInBgpExec tpInBgpExec = model.createResource().as(TpInBgpExec.class);
//            tpInBgpExec
//                .setBgpExec(bgpLe)
//                .setSelectivity(bgpRestrictedTpSel);
////            tpInBgpExec.setBenchmarkRun(expRun);
//
//        }
//    }
//}


// Update join restricted tp selectivity
// For this purpose iterate the prior computed bgpExecs
//{
//    for(SpinBgpExec bgpExec : expRoot.getBgpExecs()) {
//        SpinBgp bgp = bgpExec.getBgp();
//        for(SpinBgpNode bgpNode : bgp.getBgpNodes()) {
////    for(SpinBgp bgp : spinRoot.getBgps()) {
////        for(SpinBgpNode bgpNode : bgp.getBgpNodes()) {
//            if(bgpNode.toJenaNode().isVariable()) {
//
//                LocalExecution bgpLe = bgpNode.getSubBgp().getExtensionQuery().getLocalExecutionMap().get(expRun);
//                LocalExecution bgpNodeLe = bgpNode.getJoinExtensionQuery().getLocalExecutionMap().get(expRun);
//
//
//                Long bgpExtSize = bgpLe.getQueryExec().getResultSetSize();
//                Long bgpNodeExtSize = bgpNodeLe.getQueryExec().getResultSetSize();
//
//                BigDecimal bgpRestrictedJoinVarSel = bgpExtSize == 0 ? new BigDecimal(0) : new BigDecimal(bgpNodeExtSize).divide(new BigDecimal(bgpExtSize));
//
//                JoinVertexExec bgpVarExec = model.createResource().as(JoinVertexExec.class);
//                // bgpVarExec.setBenchmarkRun(expRun);
////                bgpVarExec.setBgpNode(bgpNode);
//                bgpVarExec
//                    .setBgpExec(bgpExec)
//                    .setBgpRestrictedSelectivitiy(bgpRestrictedJoinVarSel);
//
//                // BgpVarExec is a child of BgpExec, hence there is no need for a back pointer
//                //bgpVarExec.setBgpExec(bgpLe);
//            }
//        }
//    }
//}



//
//// LookupServiceUtils.createLookupService(indexConn, );
//// Triple t = new Triple(Vars.s, LSQ.execStatus.asNode(), Vars.o);
//Triple t = new Triple(Vars.s, Vars.p, Vars.o);
//Quad quad = new Quad(Vars.g, t);
//BasicPattern basicPatteren = new BasicPattern();
//basicPatteren.add(t);
////QuadPattern quadPattern = new QuadPattern();
////quadPattern.add(quad);
//
//DataQuery<RDFNode> dq = new DataQueryImpl<>(
//      indexConn,
//      ElementUtils.createElement(quad),
//      Vars.s,
//      new Template(basicPatteren),
//      RDFNode.class
//);
//
//
//System.out.println("Generated: " + dq.toConstructQuery());

