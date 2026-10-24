package org.puneet.cloudsimplus.hiippo.runtime;

import org.puneet.cloudsimplus.hiippo.placement.PlacementObjective;
import org.puneet.cloudsimplus.hiippo.placement.Search;
import java.io.*;
import java.nio.file.*;

/** Case-private scalar evidence; never retains optimizer populations or trace lists. */
final class EvaluationSpool implements AutoCloseable {
    private final DataOutputStream output;
    EvaluationSpool(Path path) throws IOException {
        output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)));
    }
    void accept(Search.Evaluation value) throws IOException {
        output.writeInt(value.evaluation()); output.writeInt(value.iteration()); output.writeUTF(value.phase()); output.writeInt(value.member());
        output.writeBoolean(value.feasible()); score(output,value.score()); output.writeBoolean(value.accepted());
        number(value.bestWatts()); number(value.bestObjectiveScore());
    }
    private static void score(DataOutputStream output,PlacementObjective.Score value) throws IOException {
        output.writeBoolean(value!=null);
        if(value==null) return;
        output.writeDouble(value.powerW()); output.writeDouble(value.reservedPeakUtilization());
        output.writeDouble(value.normalizedPower()); output.writeDouble(value.normalizedUtilization());
        output.writeDouble(value.objective());
    }
    private static PlacementObjective.Score score(DataInputStream input) throws IOException {
        if(!input.readBoolean()) return null;
        return new PlacementObjective.Score(input.readDouble(),input.readDouble(),input.readDouble(),input.readDouble(),input.readDouble());
    }
    private void number(Double value) throws IOException { output.writeBoolean(value!=null); if(value!=null) output.writeDouble(value); }
    private static Double number(DataInputStream input) throws IOException { return input.readBoolean()?input.readDouble():null; }
    static void replay(Path path,Search.EvaluationSink sink) throws IOException {
        if(!Files.exists(path)) return;
        try(var input=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            // EOF is only valid between whole records; truncated scalar evidence fails publication.
            while(input.available()>0) sink.accept(new Search.Evaluation(input.readInt(),input.readInt(),input.readUTF(),input.readInt(),
                input.readBoolean(),score(input),input.readBoolean(),number(input),number(input)));
        }
    }
    @Override public void close() throws IOException { output.close(); }
}
