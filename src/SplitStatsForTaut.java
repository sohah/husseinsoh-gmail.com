/*
This class is used to split the statistics file based on properties listed, such that we loop over the statistics
file and divide it by its matching or not over the taut props. This should be called where the first input is the name of the
statistics files for the prop, and the second input is the benchmark, and finally the third arguement is the prop we are interested in.

IMPORTANT: to run this correctly one has to manually populate the tautProp according to the tautology properties obtained from the expirement.
* */

import jkind.api.JKindApi;
import jkind.api.results.JKindResult;
import jkind.api.results.PropertyResult;
import jkind.api.results.Status;
import jkind.lustre.Equation;
import jkind.lustre.Program;
import jkind.lustre.parsing.LustreParseUtil;
import org.eclipse.core.runtime.NullProgressMonitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class SplitStatsForTaut {
    //tautology props, that we have not confirmed yet that they cant be tightened using unbounded check
    public static List<String> tautProp = new ArrayList<>();

    //tautology props, that we have CONFIRMED that they cant be tightened using unbounded check
    public static List<String> validTautProp = new ArrayList<>();

    public static String directory; // passed as input and it is the parent directory of the repair process for the expirement
    public static String benchmark; // passed as an input
    public static String prop; //passed as an input
    public static String mutantExistsDirectory; // this is also passed as input, and it directs to the directory of the mutant expirement, where we can find the last exists query

    public static String fileName;
    private static Path tautStatFile;
    private static Path noTautStatFile;

    //this data structure needs to maintain the insertion order.
    private static LinkedHashMap<String, String> tautMutantsToProp = new LinkedHashMap<>();
    static int timeOut = 600;

    public static void filterStatisticsFile() throws IOException {


        setupDataStructure();

        //find the mutant names that corresponds to the initially tautology props.
        fillTautMutantsToPropMap();

        //run unbounded mode for the last exists query for all mutants that were tautology, has the side effect of populating validTautProp with valid props
        runUnboundedVerification();

        splitStatFileToTaut();
    }

    //run unbounded verification for the last exists query for each mutant in tautMutantToProp map, and populates validTautProp with the valid ones. Those are later used for spliting the stat file
    private static void runUnboundedVerification() {
        // mutantExistsDirectory must be something that has the exiprement like /media/soha/DATA/MultiMutationExpr/1Mutation/ranger-discovery/src/DiscoveryExamples/WBS/

        for (Map.Entry<String, String> mutantProp : tautMutantsToProp.entrySet()) {
            String mutantName = mutantProp.getKey();

            String propWithFirstLetterCapitablized = prop.substring(0, 1).toUpperCase() + prop.substring(1);

            String exitsFileName = mutantExistsDirectory.concat("/" + propWithFirstLetterCapitablized + "/output/" + mutantName + "/minimal/" + mutantName + "_exists.lus");

            JKindResult jkindResutl = callJkind(exitsFileName);

            System.out.println("running existsFileName = " + exitsFileName);
            System.out.println("Query Result = " + jkindResutl.getPropertyResult("fail").getStatus().toString());

            if (jkindResutl.getPropertyResult("fail").getStatus() == Status.VALID)
                validTautProp.add(mutantProp.getValue());
        }
    }

    //the output of this method is a pair of the matching mutant name with its prop, this is populated in tautMutantsToProp
    public static void fillTautMutantsToPropMap() throws IOException {

        for (String currTautProp : tautProp) {
            // Open the file
            FileInputStream fstream = new FileInputStream(directory + fileName);
            BufferedReader br = new BufferedReader(new InputStreamReader(fstream));

            String strLine;

            //Read File Line By Line -- and get the mutants names along with their properties for those that are tautology and failed because of TRUE_FOR_MAX_STEPS
            while ((strLine = br.readLine()) != null) {
                if (strLine.contains(currTautProp) && strLine.contains("TRUE_FOR_MAX_STEPS")) {//if match then we need to write it to the taut file

                    //get the name of the mutant to later run its last exists query in an unbounded mode.
                    String[] statsEntry = strLine.split("\t");
                    for (int i = 0; i < statsEntry.length; i++)
                        if (statsEntry[i].contains("ROR") || statsEntry[i].contains("LOR")) {
                            tautMutantsToProp.put(statsEntry[i], currTautProp);
                            break;
                        }
                }
            }

            //Close the input stream
            fstream.close();
        }
    }

    public static void splitStatFileToTaut() throws IOException {

        assert !validTautProp.isEmpty() : "No valid taut prop to split on. It might be okay, so check if indeed there are no valid taut prop we could find";

        tautStatFile = Paths.get(directory + "/stats/" + benchmark + "_tautStatFile" + ".txt");
        noTautStatFile = Paths.get(directory + "/stats/" + benchmark + "_noTautStatFile" + ".txt");
        Files.write(tautStatFile, new ArrayList<>(), StandardCharsets.UTF_8);
        Files.write(noTautStatFile, new ArrayList<>(), StandardCharsets.UTF_8);

        ArrayList<String> tautStatEntry = new ArrayList<>();
        ArrayList<String> noTautStatEntry = new ArrayList<>();

        // Open the file
        FileInputStream fstream = new FileInputStream(directory + fileName);
        BufferedReader br = new BufferedReader(new InputStreamReader(fstream));

        String strLine;
        int propIndex = 0;


        //Read File Line By Line
        while ((strLine = br.readLine()) != null) {
            if (propIndex >= validTautProp.size()) {
                noTautStatEntry.add(strLine);
            } else {
                String currTautProp = validTautProp.get(propIndex);
                if (strLine.contains(currTautProp)) {//if match then we need to write it to the taut file
                    assert strLine.contains("TRUE_FOR_MAX_STEPS") : "tautology repair mutant must have been selected at that point because they failed due to TRUE_FOR_MAX_STEPS. Assumptions violated. Failing";
                    propIndex++; // we matched that prop, so we need to move on
                    tautStatEntry.add(strLine);
                } else  //we need to write it to non-taut file, while still looking for the prop
                    noTautStatEntry.add(strLine);
            }
        }

        Files.write(tautStatFile, tautStatEntry, StandardCharsets.UTF_8);
        Files.write(noTautStatFile, noTautStatEntry, StandardCharsets.UTF_8);

        //Close the input stream
        fstream.close();
    }

    //this needs to be manually setup for correct running
    private static void setupDataStructure() throws IOException {
        String matchingString = initializeFileName(); // at that point fileName has the right name of the stat file for the benchmark and the prop name. We return the matching string that we are looking for as well

        initializeTautProp(matchingString);


    }

    private static void initializeTautProp(String matchingString) throws IOException {
        String tautStateFile = benchmark + "TautologyDetails_all_stats.txt";
        // Open the file
        FileInputStream fstream = new FileInputStream(directory + tautStateFile);
        BufferedReader br = new BufferedReader(new InputStreamReader(fstream));

        String strLine;

        //Read File Line By Line
        while ((strLine = br.readLine()) != null) {
            if (strLine.contains(matchingString)) {
                String tautPropStr = strLine.substring(matchingString.length() + 1, strLine.length() - 1);
                String[] tautPropNames = tautPropStr.split(",");

                for (int i = 0; i < tautPropNames.length; i++) {
                    String tautPropName = tautPropNames[i];
                    tautPropName = tautPropName.replace(" ", "");
                    tautPropName = tautPropName.concat("=");
                    String bodyFileName = getCorrespondingBodyFileName();
                    //now we open the body file as a lustre file and look for the corresponding property we are looking for.

                    FileInputStream bodyFstream = new FileInputStream(bodyFileName);
                    BufferedReader bodybr = new BufferedReader(new InputStreamReader(bodyFstream));
                    String bodyLine;
                    while ((bodyLine = bodybr.readLine()) != null) {
                        if (bodyLine.contains(tautPropName)) {
                            tautProp.add(bodyLine.substring(tautPropName.length()));
                            break;
                        }
                    }
                    bodyFstream.close();

                   /* Program pgm = LustreParseUtil.program(new String(Files.readAllBytes(Paths.get(bodyFileName)), "UTF-8"));
                    List<Equation> equations = pgm.getMainNode().equations;

                    for (int j = 0; j < equations.size(); j++) {
                        Equation equation = equations.get(j);
                        String lhsName = equation.lhs.toString().replace("[", "");
                        lhsName = lhsName.replace("]", "");
                        if (lhsName.equals(tautPropName)) {
                            tautProp.add(equation.expr.toString());
                            break; // we break that loop once we found the property we are looking for and after we populate it to the arraylist that we are going to use for the spliting
                        }
                    }*/
                }

                //Close the input stream
                fstream.close();
                return;
            }
        }

        assert false : "this cant happen as it means there were matching text for the matchingString";
    }

    private static String getCorrespondingBodyFileName() {
        String bodyFileName = directory + "Body/" + benchmark + "_" + prop + ".lus";

        return bodyFileName;
    }


    private static String initializeFileName() {
        String matchingString = "tautology props for " + benchmark + ": ";
        if (benchmark.equals("wbs")) {
            if (prop.equals("prop1")) {
                fileName = "stats/wbs_stat_prop1";
                matchingString = matchingString.concat("p1");
            } else {
                fileName = "stats/wbs_stat_prop3";
                matchingString = matchingString.concat("p3");
            }
        } else if (benchmark.equals("tcas")) {
            if (prop.equals("prop1")) {
                fileName = "stats/tcas_stat_prop1";
                matchingString = matchingString.concat("p1");
            } else if (prop.equals("prop2")) {
                fileName = "stats/tcas_stat_prop2";
                matchingString = matchingString.concat("p2");
            } else {
                fileName = "stats/tcas_stat_prop4";
                matchingString = matchingString.concat("p4");
            }
        } else if (benchmark.equals("infusion")) {
            if (prop.equals("prop1")) {
                fileName = "stats/infusion_stat_prop1";
                matchingString = matchingString.concat("p1");
            } else if (prop.equals("prop2")) {
                fileName = "stats/infusion_stat_prop2";
                matchingString = matchingString.concat("p2");
            } else if (prop.equals("prop3")) {
                fileName = "stats/infusion_stat_prop3";
                matchingString = matchingString.concat("p3");
            } else if (prop.equals("prop4")) {
                fileName = "stats/infusion_stat_prop4";
                matchingString = matchingString.concat("p4");
            } else if (prop.equals("prop5")) {
                fileName = "stats/infusion_stat_prop5";
                matchingString = matchingString.concat("p5");
            } else if (prop.equals("prop6")) {
                fileName = "stats/infusion_stat_prop6";
                matchingString = matchingString.concat("p6");
            } else if (prop.equals("prop7")) {
                fileName = "stats/infusion_stat_prop7";
                matchingString = matchingString.concat("p7");
            } else if (prop.equals("prop8")) {
                fileName = "stats/infusion_stat_prop8";
                matchingString = matchingString.concat("p8");
            } else if (prop.equals("prop9")) {
                fileName = "stats/infusion_stat_prop9";
                matchingString = matchingString.concat("p9");
            } else if (prop.equals("prop10")) {
                fileName = "stats/infusion_stat_prop10";
                matchingString = matchingString.concat("p10");
            } else if (prop.equals("prop11")) {
                fileName = "stats/infusion_stat_prop11";
                matchingString = matchingString.concat("p11");
            } else if (prop.equals("prop12")) {
                fileName = "stats/infusion_stat_prop12";
                matchingString = matchingString.concat("p12");
            } else if (prop.equals("prop13")) {
                fileName = "stats/infusion_stat_prop13";
                matchingString = matchingString.concat("p13");
            } else {
                fileName = "stats/infusion_stat_prop14";
                matchingString = matchingString.concat("p14");
            }
        } else { //must be a gpca
            assert benchmark.equals("gpca");
            if (prop.equals("prop1")) {
                fileName = "stats/gpca_stat_prop1";
                matchingString = matchingString.concat("p1");
            } else if (prop.equals("prop2")) {
                fileName = "stats/gpca_stat_prop2";
                matchingString = matchingString.concat("p2");
            } else if (prop.equals("prop3")) {
                fileName = "stats/gpca_stat_prop3";
                matchingString = matchingString.concat("p3");
            } else if (prop.equals("prop4")) {
                fileName = "stats/gpca_stat_prop4";
                matchingString = matchingString.concat("p4");
            } else if (prop.equals("prop5")) {
                fileName = "stats/gpca_stat_prop5";
                matchingString = matchingString.concat("p5");
            } else if (prop.equals("prop6")) {
                fileName = "stats/gpca_stat_prop6";
                matchingString = matchingString.concat("p6");
            } else if (prop.equals("prop7")) {
                fileName = "stats/gpca_stat_prop7";
                matchingString = matchingString.concat("p7");
            } else if (prop.equals("prop8")) {
                fileName = "stats/gpca_stat_prop8";
                matchingString = matchingString.concat("p8");
            } else if (prop.equals("prop9")) {
                fileName = "stats/gpca_stat_prop9";
                matchingString = matchingString.concat("p9");
            } else {
                fileName = "stats/gpca_stat_prop10";
                matchingString = matchingString.concat("p10");
            }

        }
        return matchingString.concat(" are: ");
    }


    /**
     * Example of the passed arguments is
     * props/Multi-Mutation_1Mutation_False/
     * wbs
     * prop1
     * /media/soha/DATA/MultiMutationExpr/1Mutation/ranger-discovery/src/DiscoveryExamples/WBS
     *
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        directory = args[0];
        benchmark = args[1];
        prop = args[2];
        mutantExistsDirectory = args[3];
        filterStatisticsFile();
    }


    public static JKindResult callJkind(String fileName) {
        File file1;

        file1 = new File(fileName);

        JKindApi api = new JKindApi();
        JKindResult result = new JKindResult("");

        api.setJKindJar("../../jkindNoRand/jkind.jar");

        api.setTimeout(timeOut);

        api.execute(file1, result, new NullProgressMonitor());

        return result;
    }
}
