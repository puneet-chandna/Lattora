package org.puneet.cloudsimplus.hiippo.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.puneet.cloudsimplus.hiippo.placement.PlacementObjective;
import org.puneet.cloudsimplus.hiippo.placement.Search;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationSpoolTest {
    @TempDir Path temp;
    @Test void scalarRecordsRoundTripWithoutCandidatePopulationData() throws Exception {
        var file=temp.resolve("case.spool");
        var score=new PlacementObjective.Score(100.0,0.5,0.25,0.5,0.3);
        var first=new Search.Evaluation(1,0,"INITIAL",0,true,score,true,100.0,0.3);
        var second=new Search.Evaluation(2,1,"PREDATOR",1,false,null,false,100.0,0.3);
        try(var spool=new EvaluationSpool(file)) { spool.accept(first); spool.accept(second); }
        var records=new ArrayList<Search.Evaluation>(); EvaluationSpool.replay(file,records::add);
        assertEquals(List.of(first,second),records);
    }
    @Test void truncatedScalarRecordFailsReplay() throws Exception {
        var file=temp.resolve("case.spool");
        try(var spool=new EvaluationSpool(file)) { spool.accept(new Search.Evaluation(1,0,"INITIAL",0,true,
            new PlacementObjective.Score(100.0,0.5,0.25,0.5,0.3),true,100.0,0.3)); }
        var bytes=Files.readAllBytes(file); Files.write(file,Arrays.copyOf(bytes,bytes.length-1));
        assertThrows(IOException.class,()->EvaluationSpool.replay(file,value->{}));
    }
}
