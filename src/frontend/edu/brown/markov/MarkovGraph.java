package edu.brown.markov;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;

import edu.brown.catalog.CatalogUtil;
import edu.brown.graphs.AbstractDirectedGraph;
import edu.brown.graphs.VertexTreeWalker;
import edu.brown.graphs.VertexTreeWalker.Direction;
import edu.brown.graphs.VertexTreeWalker.TraverseOrder;
import edu.brown.markov.Vertex.Type;
import edu.brown.utils.ArgumentsParser;
import edu.brown.utils.LoggerUtil;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.StringUtil;
import edu.brown.workload.QueryTrace;
import edu.brown.workload.TransactionTrace;

/**
 * Markov Model Graph
 * @author svelagap
 * @author pavlo
 */
public class MarkovGraph extends AbstractDirectedGraph<Vertex, Edge> implements Comparable<MarkovGraph> {
    private static final long serialVersionUID = 3548405718926801012L;
    private static final Logger LOG = Logger.getLogger(MarkovGraph.class);
    private final static AtomicBoolean debug = new AtomicBoolean(LOG.isDebugEnabled());
    private final static AtomicBoolean trace = new AtomicBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    /**
     * If this global parameter is set to true, then all of the MarkovGraphs will evaluate the 
     * past partitions set at each vertex. If this is parameter is false, then all of the calculations
     * to determine the uniqueness of vertices will not include past partitions. 
     */
    public static final boolean USE_PAST_PARTITIONS = true;
    
    protected final Procedure catalog_proc;

    /**
     * Cached references to the special marker vertices
     */
    private final transient Map<Vertex.Type, Vertex> vertex_cache = new HashMap<Vertex.Type, Vertex>();

    /**
     * 
     */
    private int xact_count;

    // ----------------------------------------------------------------------------
    // CONSTRUCTORS
    // ----------------------------------------------------------------------------
    
    /**
     * Constructor
     * @param catalog_proc
     * @param basePartition
     */
//    public MarkovGraph(Procedure catalog_proc, int xact_count) {
//        super((Database) catalog_proc.getParent());
//        this.catalog_proc = catalog_proc;
//        this.xact_count = xact_count;
//    }
    public MarkovGraph(Procedure catalog_proc){
        super((Database) catalog_proc.getParent());
        this.catalog_proc = catalog_proc;
    }
    
    /**
     * Add the START, COMMIT, and ABORT vertices to the current graph
     */
    public MarkovGraph initialize() {
        for (Vertex.Type type : Vertex.Type.values()) {
            switch (type) {
                case START:
                case COMMIT:
                case ABORT:
                    Vertex v = MarkovUtil.getSpecialVertex(this.getDatabase(), type);
                    assert(v != null);
                    this.addVertex(v);
                    break;
                default:
                    // IGNORE
            } // SWITCH
        }
        return (this);
    }
    
    @Override
    public String toString() {
//        return (this.getClass().getSimpleName() + "<" + this.getProcedure().getName() + ", " + this.getBasePartition() + ">");
        return (this.getClass().getSimpleName() + "<" + this.getProcedure().getName() + ">");
    }
    
    @Override
    public int compareTo(MarkovGraph o) {
        assert(o != null);
        return (this.catalog_proc.compareTo(o.catalog_proc));
    }
    
    // ----------------------------------------------------------------------------
    // DATA MEMBER METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Return the Procedure catalog object that this Markov graph represents
     * @return
     */
    public Procedure getProcedure() {
        return catalog_proc;
    }
    
//    /**
//     * Return the base partition id that this Markov graph represents
//     * @return
//     */
//    public int getBasePartition() {
//        return this.base_partition;
//    }
    
    /**
     * 
     */
    @Override
    public boolean addVertex(Vertex v) {
        boolean ret = super.addVertex(v);
        if (ret) {
            Vertex.Type type = v.getType();
            switch (type) {
                case START:
                case COMMIT:
                case ABORT:
                    assert(!this.vertex_cache.containsKey(type)) : "Trying add duplicate " + type + " vertex";
                    this.vertex_cache.put(type, v);
                    break;
                default:
                    // Ignore others
            } // SWITCH
        }
        return (ret);
    }

    /**
     * Return the specific vertex for the state type
     * @param vtype
     * @return
     */
    protected Vertex getSpecialVertex(Vertex.Type vtype) {
        assert(vtype != Vertex.Type.QUERY);
        Vertex v = null;
        switch (vtype) {
            case START:
                v = this.getStartVertex();
                break;
            case COMMIT:
                v = this.getCommitVertex();
                break;
            case ABORT:
                v = this.getAbortVertex();
                break;
            default:
                // Ignore others
        } // SWITCH
        return (v);
    }
    
    /**
     * Get the vertex based on it's unique identifier. This is a combination of
     * the query, the partitions the query is touching and where the query is in
     * the transaction
     * 
     * @param a
     * @param partitions
     *            set of partitions this query is touching
     * @param queryInstanceIndex
     *            query's location in transactiontrace
     * @return
     */
    protected Vertex getVertex(Statement a, Set<Integer> partitions, Set<Integer> past_partitions, int queryInstanceIndex) {
        for (Vertex v : this.getVertices()) {
            if (v.isEqual(a, partitions, past_partitions, queryInstanceIndex)) {
                return v;
            }
        }
        return null;
    }
    
    /**
     * Return an immutable list of all the partition ids in our catalog
     * @return
     */
    protected List<Integer> getAllPartitions() {
        return (CatalogUtil.getAllPartitionIds(this.getDatabase()));
    }
    
    /**
     * Gives all edges to the set of vertices vs in the MarkovGraph g
     * @param vs
     * @param g
     * @return
     */
    public Set<Edge> getEdgesTo(Set<Vertex> vs) {
        Set<Edge> edges = new HashSet<Edge>();
        for (Vertex v : vs) {
            if (v != null) edges.addAll(this.getInEdges(v));
        }
        return edges;
    }
    
    // ----------------------------------------------------------------------------
    // STATISTICAL MODEL METHODS
    // ----------------------------------------------------------------------------

    /**
     * Calculate the probabilities for this graph This invokes the static
     * methods in Vertex to calculate each probability
     */
    public void calculateProbabilities() {
        // Reset all probabilities
//        for (Vertex v : this.getVertices()) {
//            v.resetAllProbabilities();
//        } // FOR
        
        // We first need to calculate the edge probabilities because the probabilities
        // at each vertex are going to be derived from these
        this.calculateEdgeProbabilities();
        
        // Then traverse the graph and calculate the vertex probability tables
        this.calculateVertexProbabilities();
    }

    /**
     * Calculate vertex probabilities
     */
    protected void calculateVertexProbabilities() {
        if (trace.get()) LOG.trace("Calculating Vertex probabilities for " + this);
        
        final Set<Edge> visited_edges = new HashSet<Edge>();
        final List<Integer> all_partitions = this.getAllPartitions();
        
        for (Vertex v : new Vertex[]{ this.getCommitVertex(), this.getAbortVertex() }) {
            new VertexTreeWalker<Vertex>(this, TraverseOrder.LONGEST_PATH, Direction.REVERSE) {
                @Override
                protected void callback(Vertex element) {
                    if (trace.get()) LOG.trace("BEFORE: " + element + " => " + element.getSingleSitedProbability());
//                    if (element.isSingleSitedProbablitySet() == false) element.setSingleSitedProbability(0.0);
                    Type vtype = element.getType(); 
                    
                    // COMMIT/ABORT is always single-partitioned!
                    if (vtype == Vertex.Type.COMMIT || vtype == Vertex.Type.ABORT) {
                        if (trace.get()) LOG.trace(element + " is single-partitioned!");
                        element.setSingleSitedProbability(1.0f);
                        
                        // And DONE at all partitions!
                        // And will not Read/Write Probability
                        for (Integer partition : all_partitions) {
                            element.setDoneProbability(partition, 1.0f);
                            element.setReadOnlyProbability(partition, 0.0f);
                            element.setWriteProbability(partition, 0.0f);
                        } // FOR
                        
                        // Abort Probability
                        if (vtype == Vertex.Type.ABORT) {
                            element.setAbortProbability(1.0f);
                        } else {
                            element.setAbortProbability(0.0f);
                        }
    
                    } else {
                        
                        // If the current vertex is not single-partitioned, then we know right away
                        // that the probability should be zero and we don't need to check our successors
                        // We define a single-partition vertex to be a query that accesses only one partition
                        // that is the same partition as the base/local partition. So even if the query accesses
                        // only one partition, if that partition is not the same as where the java is executing,
                        // then we're going to say that it is multi-partitioned
                        boolean element_islocalonly = element.isLocalPartitionOnly(); 
                        if (element_islocalonly == false) {
                            if (trace.get()) LOG.trace(element + " NOT is single-partitioned!");
                            element.setSingleSitedProbability(0.0f);
                        }

                        Statement catalog_stmt = element.getCatalogItem();
                        
                        Collection<Edge> edges = MarkovGraph.this.getOutEdges(element);
                        for (Edge e : edges) {
                            if (visited_edges.contains(e)) continue;
                            Vertex successor = MarkovGraph.this.getDest(e);
                            assert(successor != null);
                            assert(successor.isSingleSitedProbabilitySet()) : "Setting " + element + " BEFORE " + successor;

                            // Single-Partition Probability
                            // If our vertex only touches the base partition, then we need to calculate the 
                            // single-partition probability as the sum of the the edge weights times our
                            // successors' single-partition probability
                            if (element_islocalonly) {
                                float prob = (float)(e.getProbability() * successor.getSingleSitedProbability());
                                element.addSingleSitedProbability(prob);
                                if (trace.get()) LOG.trace(element + " --" + e + "--> " + successor + String.format(" [%f * %f = %f]", e.getProbability(), successor.getSingleSitedProbability(), prob) + "\nprob = " + prob);
                            }
                            
                            // Abort Probability
                            element.addAbortProbability((float)(e.getProbability() * successor.getAbortProbability()));
                            
                            // Done/Read/Write At Partition Probability
                            for (Integer partition : all_partitions) {
                                assert(successor.isDoneProbabilitySet(partition)) : "Setting " + element + " BEFORE " + successor;
                                assert(successor.isReadOnlyProbabilitySet(partition)) : "Setting " + element + " BEFORE " + successor;
                                assert(successor.isWriteProbabilitySet(partition)) : "Setting " + element + " BEFORE " + successor;
                                
                                // This vertex accesses this partition
                                if (element.getPartitions().contains(partition)) {
                                    element.setDoneProbability(partition, 0.0f);
                                    
                                    // Figure out whether it is a read or a write
                                    if (catalog_stmt.getReadonly()) {
                                        element.addWriteProbability(partition, (float)(e.getProbability() * successor.getWriteProbability(partition)));
                                        element.addReadOnlyProbability(partition, (float)(e.getProbability() * successor.getReadOnlyProbability(partition)));
                                    } else {
                                        element.setWriteProbability(partition, 1.0f);
                                        element.setReadOnlyProbability(partition, 0.0f);
                                    }
                                    
                                // This vertex doesn't access the partition, but successor vertices might so
                                // the probability is based on the edge probabilities 
                                } else {
                                    element.addDoneProbability(partition, (float)(e.getProbability() * successor.getDoneProbability(partition)));
                                    element.addWriteProbability(partition, (float)(e.getProbability() * successor.getWriteProbability(partition)));
                                    element.addReadOnlyProbability(partition, (float)(e.getProbability() * successor.getReadOnlyProbability(partition)));
                                }
                            } // FOR (PartitionId)
                        } // FOR (Edge)
                    }
                    if (trace.get()) LOG.trace("AFTER: " + element + " => " + element.getSingleSitedProbability());
                    if (trace.get()) LOG.trace(StringUtil.repeat("-", 40));
                }
            }.traverse(v);
        } // FOR (COMMIT, ABORT)
    }

    /**
     * Calculates the probabilities for each edge to be traversed
     */
    public void calculateEdgeProbabilities() {
        Collection<Vertex> vertices = this.getVertices();
        for (Vertex v : vertices) {
            for (Edge e : getOutEdges(v)) {
                e.setProbability(v.getTotalHits());
            }
        }
    }

    /**
     * Normalizes the times kept during online tallying of execution times.
     * TODO (svelagap): What about aborted transactions? Should they be counted in the normalization?
     */
    protected void normalizeTimes() {
        Map<Long, Long> stoptimes = this.getCommitVertex().getInstanceTimes();
        for (Vertex v : this.getVertices()) {
            v.normalizeInstanceTimes(stoptimes);
        }
    }
    
    /**
     * Checks to make sure the graph doesn't contain nonsense. We make sure
     * execution times and probabilities all add up correctly.
     * 
     * @return whether graph contains sane data
     */
    public boolean isValid() {
        double EPSILON = 0.00001;
        for (Vertex v : getVertices()) {
            double sum = 0.0;
            Set<Vertex> seen_vertices = new HashSet<Vertex>(); 
            for (Edge e : this.getOutEdges(v)) {
                Vertex v1 = this.getOpposite(v, e);
                
                // Make sure that each vertex only has one edge to another vertex
                assert(!seen_vertices.contains(v1)) : "Vertex " + v + " has more than one edge to vertex " + v1;
                seen_vertices.add(v1);
                
                // Calculate total edge probabilities
                double edge_probability = e.getProbability(); 
                assert(edge_probability >= 0.0) : "Edge " + e + " probability is " + edge_probability;
                assert(edge_probability <= 1.0) : "Edge " + e + " probability is " + edge_probability;
                sum += e.getProbability();
            } // FOR
            
            if (sum - 1.0 > EPSILON && getInEdges(v).size() != 0) {
                return false;
            }
            sum = 0.0;
            // Andy asks: Should this be getInEdges()?
            // Saurya replies: No, the probability of leaving this query should be 1.0, coming
            //                 in could be more. There could be two vertices each with .75 probability
            //                 of getting into this vertex, 1.5 altogether
            for (Edge e : getOutEdges(v)) { 
                sum += e.getProbability();
            }
            if (sum - 1.0 > EPSILON && getOutEdges(v).size() != 0) {
                return false;
            }
        }
        return true;
    }

    public boolean shouldRecompute(int instance_count, double recomputeTolerance){
        double VERTEX_PROPORTION = 0.5f; //If VERTEX_PROPORTION of 
        int count = 0;
        for(Vertex v: this.getVertices()){
            if(v.shouldRecompute(instance_count, recomputeTolerance, xact_count)){
                count++;
            }
        }
        return (count*1.0/getVertices().size()) >= VERTEX_PROPORTION;
    }
    // ----------------------------------------------------------------------------
    // XACT PROCESSING METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Increases the weight between two vertices. Creates an edge if one does
     * not exist, then increments the source vertex's count and the edge's count
     * 
     * @param source
     *            - the source vertex
     * @param dest
     *            - the destination vertex
     */
    public Edge addToEdge(Vertex source, Vertex dest) {
        Edge e = this.findEdge(source, dest);
        if (e == null) {
            e = new Edge(this);
            this.addEdge(e, source, dest);
        }
        source.increment();
        e.increment();
        return (e);
    }
    
    /**
     * For a given TransactionTrace object, process its contents and update our
     * graph
     * 
     * @param txn_trace - The TransactionTrace to process and update the graph with
     * @param pest - The PartitionEstimator to use for estimating where things go
     */
    public List<Vertex> processTransaction(TransactionTrace txn_trace, PartitionEstimator pest) throws Exception {
        Procedure catalog_proc = txn_trace.getCatalogItem(this.getDatabase());
        Vertex previous = this.getStartVertex();
        previous.addExecutionTime(txn_trace.getStopTimestamp() - txn_trace.getStartTimestamp());

        final List<Vertex> path = new ArrayList<Vertex>();
        path.add(previous);
        
        Map<Statement, AtomicInteger> query_instance_counters = new HashMap<Statement, AtomicInteger>();
        for (Statement catalog_stmt : catalog_proc.getStatements()) {
            query_instance_counters.put(catalog_stmt, new AtomicInteger(0));
        } // FOR
        
        final int base_partition = pest.getBasePartition(txn_trace);
        
        // The past partitions is all of the partitions that this txn has touched,
        // included the base partition where the java executes
        Set<Integer> past_partitions = new HashSet<Integer>();
        // XXX past_partitions.add(this.getBasePartition());
        
        // -----------QUERY TRACE-VERTEX CREATION--------------
        for (QueryTrace query_trace : txn_trace.getQueries()) {
            Set<Integer> partitions = pest.getPartitions(query_trace, base_partition);
            assert(partitions != null);
            assert(!partitions.isEmpty());
            Statement catalog_stmnt = query_trace.getCatalogItem(this.getDatabase());

            int queryInstanceIndex = query_instance_counters.get(catalog_stmnt).getAndIncrement(); 
            Vertex v = this.getVertex(catalog_stmnt, partitions, past_partitions, queryInstanceIndex);
            if (v == null) {
                // If no such vertex exists we simply create one
                v = new Vertex(catalog_stmnt, Vertex.Type.QUERY, queryInstanceIndex, partitions, new HashSet<Integer>(past_partitions));
                this.addVertex(v);
            }
            // Add to the edge between the previous vertex and the current one
            this.addToEdge(previous, v);

            if (query_trace.getAborted()) {
                // Add an edge between the current vertex and the abort vertex
                this.addToEdge(v, this.getAbortVertex());
            }
            // Annotate the vertex with remaining execution time
            v.addExecutionTime(txn_trace.getStopTimestamp() - query_trace.getStartTimestamp());
            previous = v;
            path.add(v);
            past_partitions.addAll(partitions);
        } // FOR
        if (!previous.equals(this.getAbortVertex())) {
            this.addToEdge(previous, this.getCommitVertex());
            path.add(this.getCommitVertex());
        }
        // -----------END QUERY TRACE-VERTEX CREATION--------------
        this.xact_count++;
        return (path);
    }
    
    // ----------------------------------------------------------------------------
    // UTILITY METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Reset the instance hit counters
     * XXX: This assumes that this will not be manipulated concurrently, no
     * other transaction running at the same time
     */
    public synchronized void resetCounters() {
        for (Vertex v : this.getVertices()) {
            v.setInstancehits(0);
        }
        for (Edge e : this.getEdges()) {
            e.setInstancehits(0);
        }
    }
    
    /**
     * Update the instance hits for the graph's elements and recalculate probabilities
     */
    public synchronized void recomputeGraph() {
        this.normalizeTimes();
        for (Vertex v : this.getVertices()) {
            v.incrementTotalhits(v.getInstancehits());
        }
        for (Edge e : this.getEdges()) {
            e.incrementHits(e.getInstancehits());
        }
        this.calculateProbabilities();
    }
    
    /**
     * 
     * @return The number of xacts used to make this MarkovGraph
     */
    public int getTransactionCount() {
        return xact_count;
    }    
    public void setTransactionCount(int xact_count){
        this.xact_count = xact_count;
    }
    /**
     * Increase the transaction count for this MarkovGraph by one
     */
    public void incrementTransasctionCount() {
       this.xact_count++; 
    }
    
    /**
     * For the given Vertex type, return the special vertex
     * @param type - the Vertex type (cannot be a regular query)
     * @return the single vertex for that type  
     */
    protected final Vertex getVertex(Vertex.Type type) {
        Vertex ret = this.vertex_cache.get(type);
        assert(ret != null) : "The special vertex for type " + type + " is null";
        return (ret);
    }
    
    /**
     * Get the start vertex for this MarkovGraph
     * @return
     */
    public final Vertex getStartVertex() {
        return (this.getVertex(Vertex.Type.START));
    }
    /**
     * Get the stop vertex for this MarkovGraph
     * @return
     */
    public final Vertex getCommitVertex() {
        return (this.getVertex(Vertex.Type.COMMIT));
    }
    /**
     * Get the abort vertex for this MarkovGraph
     * @return
     */
    public final Vertex getAbortVertex() {
        return (this.getVertex(Vertex.Type.ABORT));
    }

    // ----------------------------------------------------------------------------
    // YE OLDE MAIN METHOD
    // ----------------------------------------------------------------------------

    /**
     * To load in the workloads and see their properties, we use this method.
     * There are still many workloads that have problems running.
     * 
     * @param args
     * @throws InterruptedException
     * @throws InvocationTargetException
     */
    public static void main(String vargs[]) throws Exception {
        ArgumentsParser args = ArgumentsParser.load(vargs);
        args.require(ArgumentsParser.PARAM_CATALOG, ArgumentsParser.PARAM_WORKLOAD);
        final PartitionEstimator p_estimator = new PartitionEstimator(args.catalog_db, args.hasher);
        MarkovGraphsContainer graphs_per_partition = MarkovUtil.createBasePartitionGraphs(args.catalog_db, args.workload, p_estimator);
        
//
//        Map<Procedure, Pair<Integer, Integer>> counts = new HashMap<Procedure, Pair<Integer, Integer>>();
//        for (Procedure catalog_proc : args.catalog_db.getProcedures()) {
//            int vertexcount = 0;
//            int edgecount = 0;
//            
//            for (int i : partitions) {
//                Pair<Procedure, Integer> current = new Pair<Procedure, Integer>(catalog_proc, i);
//                vertexcount += partitionGraphs.get(current).getVertexCount();
//                edgecount += partitionGraphs.get(current).getEdgeCount();
//            } // FOR
//            counts.put(catalog_proc, new Pair<Integer, Integer>(vertexcount, edgecount));
//        } // FOR
//        for (Procedure pr : counts.keySet()) {
//            System.out.println(pr + "," + args.workload + "," + args.workload_xact_limit + ","
//                    + counts.get(pr).getFirst() + ","
//                    + counts.get(pr).getSecond());
//        } // FOR
        
        //
        // Save the graphs
        //
        if (args.hasParam(ArgumentsParser.PARAM_MARKOV_OUTPUT)) {
            LOG.info("Writing graphs out to " + args.getParam(ArgumentsParser.PARAM_MARKOV_OUTPUT));
            MarkovUtil.save(graphs_per_partition, args.getParam(ArgumentsParser.PARAM_MARKOV_OUTPUT));
//            for (Integer partition : graphs_per_partition.keySet()) {
//                for (MarkovGraph g : graphs_per_partition.get(partition)) {
//                    String name = g.getProcedure() + "_" + partition;
//                    String contents = GraphvizExport.export(g, name);
//                    FileUtil.writeStringToFile("./graphs/" + name + ".dot", contents);
//                }
//            }
            
        }
    }
}