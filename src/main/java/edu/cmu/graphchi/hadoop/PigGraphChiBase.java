package edu.cmu.graphchi.hadoop;

import edu.cmu.graphchi.LoggingInitializer;
import edu.cmu.graphchi.aggregators.ForeachCallback;
import edu.cmu.graphchi.aggregators.VertexAggregator;
import edu.cmu.graphchi.datablocks.FloatConverter;
import edu.cmu.graphchi.preprocessing.EdgeProcessor;
import edu.cmu.graphchi.preprocessing.FastSharder;
import edu.cmu.graphchi.preprocessing.HDFSGraphLoader;
import edu.cmu.graphchi.preprocessing.VertexIdTranslate;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.pig.*;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigTextInputFormat;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.util.Utils;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Special PIG loader that wraps a graphchi application.
 */
public abstract class PigGraphChiBase  extends LoadFunc implements LoadMetadata {

    private static final Logger logger = LoggingInitializer.getLogger("pig-graphchi-base");
    private String location;


    // Example: (vertex:int, value:float)"
    protected abstract String getSchemaString();

    @Override
    public ResourceSchema getSchema(String str, Job job) throws IOException {
        // Utils.getSchemaFromString("(b:bag{f1: chararray, f2: int})");
        ResourceSchema s = new ResourceSchema(Utils.getSchemaFromString(getSchemaString()));
        return s;
    }

    @Override
    public ResourceStatistics getStatistics(String s, Job job) throws IOException {
        return null;
    }

    @Override
    public String[] getPartitionKeys(String s, Job job) throws IOException {
        return null; // Disable partition
    }

    @Override
    public void setPartitionFilter(Expression expression) throws IOException {
    }

    @Override
    public InputFormat getInputFormat() throws IOException {
        return new PigTextInputFormat();
    }

    protected abstract int getNumShards();

    protected String getGraphName() {
        return "pigudfgraph";
    }

    @Override
    public void setLocation(String location, Job job)
            throws IOException {
        System.out.println("Set location: " + location);
        System.out.println("Job: " + job);
        PigTextInputFormat.setInputPaths(job, location);
        this.location = location;
    }


    protected abstract void run() throws Exception;



    protected abstract FastSharder createSharder(String graphName, int numShards) throws IOException;

    @Override
    public void prepareToRead(RecordReader recordReader, PigSplit pigSplit) throws IOException {

        try {

            int j = 0;
            for(String s : pigSplit.getLocations()) {
                System.out.println((j++) + "Split : " + s);
            }
            System.out.println("Num paths: " + pigSplit.getNumPaths());
            System.out.println("" + pigSplit.getConf());
            System.out.println("split index " + pigSplit.getSplitIndex());


            if (pigSplit.getSplitIndex() > 0) {
                throw new RuntimeException("Split index > 0 -- this mapper will die (expected, not an error).");
            }

            final FastSharder sharder = createSharder(this.getGraphName(), this.getNumShards());

            HDFSGraphLoader hdfsLoader = new HDFSGraphLoader(this.location, new EdgeProcessor<Float>() {
                @Override
                public Float receiveEdge(int from, int to, String token) {
                    try {
                        sharder.addEdge(from, to, token);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }

                @Override
                public void receiveVertexValue(int vertexId, String token) {

                }
            });

            hdfsLoader.load(pigSplit.getConf());
            sharder.process();

            logger.info("Starting to run");
            run();
            logger.info("Ready");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected abstract Tuple getNextResult(TupleFactory tupleFactory) throws ExecException;

    @Override
    public Tuple getNext() throws IOException {
        return getNextResult(TupleFactory.getInstance());
    }

}
