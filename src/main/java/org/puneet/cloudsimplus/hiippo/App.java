package org.puneet.cloudsimplus.hiippo;

import org.puneet.cloudsimplus.hiippo.runtime.RunConfig;
import org.puneet.cloudsimplus.hiippo.runtime.RunOutput;
import org.puneet.cloudsimplus.hiippo.runtime.RunCoordinator;
import org.puneet.cloudsimplus.hiippo.runtime.ScenarioConfig;
import org.puneet.cloudsimplus.hiippo.runtime.ScenarioOutput;
import org.puneet.cloudsimplus.hiippo.runtime.StressConfig;
import org.puneet.cloudsimplus.hiippo.runtime.StressOutput;
import org.puneet.cloudsimplus.hiippo.runtime.StaticCampaignRunner;
import java.nio.file.Path;
import java.util.*;

/** Supported executable entry point. Parsing has no output side effects. */
public final class App {
    private static final String USAGE = "Usage: java -jar lattora.jar --profile smoke|explore|research [--config file.properties] [--output-dir directory] [--debug]\n       --profile stress (see ./run-stress.sh --help)\n       --profile scenario --config scenario.json (see SCENARIOS.md)\n       --help | --version";
    private static final Set<String> SCENARIO_FLAGS=Set.of("--profile","--config","--output-dir");
    public static void main(String[] args) { System.exit(run(args)); }
    static int run(String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            System.out.println(USAGE); return 0;
        }
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("Lattora " + RunOutput.version()); return 0;
        }
        for(int i=0;i+1<args.length;i++) if(args[i].equals("--profile") && args[i+1].equals("stress")) {
            final StressConfig stress;
            try { stress=StressConfig.parse(args); }
            catch(IllegalArgumentException | ArithmeticException e) { System.err.println("CONFIG_ERROR: "+e.getMessage()); return 2; }
            try(var output=StressOutput.create(stress)) {
                System.out.println("COMPLETE: run retained at "+StaticCampaignRunner.execute(stress,output)); return 0;
            } catch(Exception e) { System.err.println("RUN_ERROR: "+e.getMessage()); return 1; }
        }
        for(int i=0;i+1<args.length;i++) if(args[i].equals("--profile") && args[i+1].equals("scenario")) {
            final ScenarioConfig scenario; final Path outputRoot;
            try {
                var options=options(args,SCENARIO_FLAGS);
                if(!options.containsKey("--config")) throw new IllegalArgumentException("--config is required");
                scenario=ScenarioConfig.load(Path.of(options.get("--config")));
                outputRoot=Path.of(options.getOrDefault("--output-dir","results/scenario"));
            }
            catch(IllegalArgumentException | ArithmeticException e) { System.err.println("CONFIG_ERROR: "+e.getMessage()); return 2; }
            catch(Exception e) { System.err.println("IO_ERROR: "+e.getMessage()); return 1; }
            try(var output=ScenarioOutput.create(scenario,outputRoot)) {
                System.out.println("COMPLETE: run retained at "+StaticCampaignRunner.execute(scenario,output)); return 0;
            } catch(Exception e) { System.err.println("RUN_ERROR: "+e.getMessage()); return 1; }
        }
        final RunConfig config;
        try { config = RunConfig.parse(args); }
        catch (IllegalArgumentException e) { System.err.println("CONFIG_ERROR: " + e.getMessage()); return 2; }
        catch (Exception e) { System.err.println("IO_ERROR: " + e.getMessage()); return 1; }
        try (var output = RunOutput.create(config)) {
            RunCoordinator.execute(config,output);
            System.out.println("COMPLETE: run retained at " + output.directory());
            return 0;
        } catch (Exception e) { System.err.println("RUN_ERROR: " + e.getMessage()); return 1; }
    }
    /** Exact flag set: unknown, duplicated and valueless options are rejected before any output exists. */
    private static Map<String,String> options(String[] args,Set<String> allowed) {
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i++) {
            String key=args[i];
            if(!allowed.contains(key)) throw new IllegalArgumentException("Unknown scenario option: "+key);
            if(options.containsKey(key)) throw new IllegalArgumentException("Duplicate option: "+key);
            if(++i==args.length || args[i].startsWith("--") || args[i].isBlank())
                throw new IllegalArgumentException("Missing value for "+key);
            options.put(key,args[i]);
        }
        return options;
    }
}
