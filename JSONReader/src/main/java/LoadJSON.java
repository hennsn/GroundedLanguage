import JSON.Note;
import JSON.Task;
import com.google.gson.Gson;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.util.StringUtils;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.regex.Pattern;

public class LoadJSON {
  private static final Gson gsonReader = new Gson();
  static final Pattern whitespace_pattern = Pattern.compile("\\s+");
  private static final Pattern dash_pattern = Pattern.compile("_");
  private static MaxentTagger tagger = new MaxentTagger(MaxentTagger.DEFAULT_JAR_PATH);
  private static final HashMap<String,Integer> Vocab = new HashMap<>();

  /**
   * Deserialize the JSONs
   */
  public static ArrayList<Task> readJSON(String filename) throws IOException {
    BufferedReader BR = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(new File(filename))), "UTF-8"));
    ArrayList<Task> Tasks = new ArrayList<>();
    String line;
    while ((line = BR.readLine()) != null)
      Tasks.add(gsonReader.fromJson(line, Task.class));

    System.out.println("Read " + filename);
    return Tasks;
  }

  /**
   * Compute most frequent words by POS tag
   */
  public static void statistics(ArrayList<Task> tasks) throws IOException {
    BufferedWriter Sents = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
        new FileOutputStream(new File("Sentences.txt.gz"))), "UTF-8"));
    HashMap<String,HashMap<String,Integer>> tagTokenMap = new HashMap<>();
    HashMap<String,Integer> tokenMap;
    String taggedString;
    String[] tokens;
    String[] taggedTokens;
    for (Task task : tasks) {
      for (Note note : task.notes) {
        if (note.type.equals("A0")) {
          for (String utterance : note.notes) {
            taggedString = tagger.tagString(utterance.replace(","," , "));
            Sents.write(taggedString + "\n");
            tokens = taggedString.split("\\s+");
            for (String tok : tokens) {
              taggedTokens = tok.split("_");
              String tag = taggedTokens[1]; //UPOS(taggedTokens[1]);
              tokenMap = tagTokenMap.get(tag);
              if (tokenMap == null) {
                tagTokenMap.put(tag, new HashMap<>());
                tokenMap = tagTokenMap.get(tag);
              }
              String word = taggedTokens[0].toLowerCase();
              if (!tokenMap.containsKey(word))
                tokenMap.put(word, 0);
              tokenMap.put(word, tokenMap.get(word) + 1);
            }
          }
        }
      }
    }
    Sents.close();

    ArrayList<Tuple> tuples = new ArrayList<>();
    for (String tag : tagTokenMap.keySet()) {
      tuples.add(new Tuple(tag, tagTokenMap.get(tag).size()));
    }
    Collections.sort(tuples);

    BufferedWriter BW = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
        new FileOutputStream(new File("TypeDistribution.txt.gz"))), "UTF-8"));
    for (Tuple tuple : tuples) {
      BW.write(String.format("%-5d  %-5s\n", (int)tuple.value(), tuple.content()));
      ArrayList<Tuple> words = new ArrayList<>();
      for (String word : tagTokenMap.get(tuple.content()).keySet()) {
        words.add(new Tuple(word, tagTokenMap.get(tuple.content()).get(word)));
      }
      Collections.sort(words);
      for (int i = 0; i < words.size(); ++i)
        if (words.get(i).value() > 1)
          BW.write(String.format("   %-4d %-8s\n", (int)words.get(i).value(), words.get(i).content()));
      BW.write("\n");
    }
    BW.close();
  }

  /**
   * Map every word which occurs at least twice to an integer
   */
  public static void computeVocabulary(ArrayList<Task> tasks) {
    // Compute the counts
    HashMap<String, Integer> counts = new HashMap<>();
    for (Task task : tasks) {
      for (Note note : task.notes) {
        if (note.type.equals("A0")) {
          for (String utterance : note.notes) {
            for (String word : tokenize(utterance)) {
              if (!counts.containsKey(word))
                counts.put(word, 0);
              counts.put(word, counts.get(word) + 1);
            }
          }
        }
      }
    }

    Vocab.put("<unk>",1);
    counts.keySet().stream().filter(w -> counts.get(w) > 1).forEachOrdered(w -> Vocab.put(w, Vocab.size() + 1));
    System.out.println(String.format("Created Vocabulary: %d of %d", Vocab.size(), counts.size()));
  }

  /**
   * Extract the block-id that moved
   * @param world_t
   * @param world_tp1
   * @return
   */
  public static int getSource(double[][] world_t, double[][] world_tp1) {
    for (int i = 0; i < world_t.length; ++i) {
      if (world_t[i][0] != world_tp1[i][0] || world_t[i][1] != world_tp1[i][1] || world_t[i][2] != world_tp1[i][2])
        return i;
    }
    System.err.println("We did not find a source block");
    return -1;
  }

  /**
   * Euclidean Distance (in block-lengths)
   */
  public static double distance(double[] A, double[] B) {
    return Math.sqrt(Math.pow(A[0] - B[0], 2) + Math.pow(A[1] - B[1], 2) + Math.pow(A[2] - B[2], 2)) / 0.1524;
  }

  /**
   * Use the text to choose possible reference blocks
   */
  public static Set<Integer> getPossibleTargets(int source, String[] tokenized, String decoration) {
    // Set of brands for labeling blocks
    String[] brands = {"adidas", "bmw", "burger king", "coca cola", "esso", "heineken", "hp", "mcdonalds",
        "mercedes benz", "nvidia", "pepsi", "shell", "sri", "starbucks", "stella artois",
        "target", "texaco", "toyota", "twitter", "ups"};

    // Set of digits for labeling blocks
    String[] digits = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"};

    String[] cleanup = {"the", "to", "on", "right", "line", "then", "even", "for", "them", "sit"};

    HashSet<String> words = new HashSet<>();
    words.addAll(Arrays.asList(tokenized));
    words.removeAll(Arrays.asList(cleanup));

    HashSet<Integer> blocks = new HashSet<>();
    if (decoration.equals("logo")) {
      ArrayList<String> brandparts = new ArrayList<>();
      String brand;
      // Try and find brands
      for (int brandid = 0 ; brandid < brands.length; ++brandid) {
        brand = brands[brandid];
        brandparts.clear();
        brandparts.addAll(Arrays.asList(whitespace_pattern.split(brand)));
        brandparts.add(brand.replace(" ", "-"));
        if (brand.equals("coca cola"))
          brandparts.add("coke");

        for (String part : brandparts) {
          for (String word : words) {
            if (part.equals(word) || (word.length() > 2  && StringUtils.editDistance(part, word) < 2))
              blocks.add(brandid);
          }
        }
      }
    } else if (decoration.equals("digit")) {
      // Are we using written digits (one, two, ...)
      HashSet<Integer> dblocks = new HashSet<>();
      String digit;
      for (int digitid = 0; digitid < digits.length; ++digitid) {
        digit = digits[digitid];
        for (String word : words) {
          if (StringUtils.editDistance(digit, word) < 2)
            dblocks.add(digitid);
        }
      }

      // Or numbers (1, 2, ...)
      HashSet<Integer> nblocks = new HashSet<>();
      for (int numeral = 1; numeral < 21; ++numeral) {
        if (words.contains(String.valueOf(numeral)) || words.contains(String.format("%dth", numeral)))
          nblocks.add(numeral - 1);
      }
      blocks = dblocks.size() > nblocks.size() ?  dblocks : nblocks;
    } else {
      return blocks;
    }

    if (blocks.contains(source))
      blocks.remove(source);
    return blocks;
  }

  /**
   * Return the closest reference block to the goal location (from the set returned by getPossibleTargets)
   */
  public static int getTarget(int source, String[] tokenized, double[][] world, String decoration) {
    Set<Integer> blocks = getPossibleTargets(source, tokenized, decoration);
    if (!blocks.isEmpty()) {
      double maxD = 100000;
      double dist;
      int target = -1;
      for (int block : blocks) {
        if (block < world.length) {
          dist = distance(world[block], world[source]);
          if (dist < maxD) {
            maxD = dist;
            target = block;
          }
        }
      }
      return target;
    } else {
      return source;
    }
  }

  /**
   * Return Relative position of the source's new destination as compared to a reference block
   */
  public static int getRP(double[] source, double[] target) {
    // Amended Ozan RP scheme of Source relative to Target
    //  1 2 3
    //  4 5 6
    //  7 8 9
    int dx = (int)Math.signum(source[0] - target[0]);
    int dz = (int)Math.signum(source[2] - target[2]);
    switch (dx) {
      case -1:
        switch (dz) {
          case -1:  // if dx < 0 and dz < 0 SW
            return 7;
          case 0:   // if dx < 0 and dz = 0 W
            return 4;
          case 1:   // if dx < 0 and dz > 0 NW
            return 1;
        }
      case 1:
        switch (dz) {
          case -1:  // if dx > 0 and dz < 0 SE
            return 9;
          case 0:   // if dx > 0 and dz = 0 E
            return 6;
          case 1:   // if dx > 0 and dz > 0 NE
            return 3;
        }
      case 0:
        switch (dz) {
        case -1:    // if dx = 0 and dz < 0 S
          return 8;
        case 0:     // if dx = 0 and dz = 0 TOP
          return 5;
        case 1:     // if dx = 0 and dz > 0 N
          return 2;
      }
    }
    return -1;
  }

  /**
   * Returns a flattened world representation (20x3) --> 60D vector
   */
  public static String getWorld(double[][] world) {
    double[] locs = new double[60];
    Arrays.fill(locs, -1);
    for (int i = 0; i < world.length; ++i) {
      locs[3*i] = world[i][0];
      locs[3*i + 1] = world[i][1];
      locs[3*i + 2] = world[i][2];
    }
    String toRet = "";
    for (double d : locs)
      toRet += String.format("%-5.2f ", d);
    return toRet;
  }

  /**
   * Returns a tokenized string[]
   */
  public static String[] tokenize(String utterance) {
    String[] tagged = whitespace_pattern.split(tagger.tagString(utterance.replace(",", " , ")));
    for (int i = 0; i < tagged.length; ++i)
      tagged[i] = dash_pattern.split(tagged[i])[0].toLowerCase();
    return tagged;
  }

  /**
   * Convert utterance to a sparse vector.  Words are UNKed according to Vocab dictionary
   */
  public static String unkUtterance(String[] tokenized) {
    String toRet = "";
    for (String word : tokenized) {
      if (!Vocab.containsKey(word))
        toRet += "1  ";
      else
        toRet += String.format("%-2d ", Vocab.get(word));
    }
    return toRet;
  }

  /**
   * Takes a series of tasks and extracts prediction and conditioning information based on Configuration file.
   * Results is a sparse matrix (ints) or floats
   */
  public static void createMatrix(ArrayList<Task> data, String filename) throws IOException {
    BufferedWriter BW = TextFile.Writer(filename);
    for (Task task : data) {
      for (Note note : task.notes) {
        if (note.type.equals("A0")) {
          for (String utterance : note.notes) {
            // Compute predictions
            int source = -1, target = -1;
            for (Information info : Configuration.predict) {
              switch (info) {
                case Source:
                  source = getSource(task.states[note.start], task.states[note.finish]);
                  BW.write(String.format(" %d ", source));
                  break;
                case Target:
                  target = getTarget(source, tokenize(utterance), task.states[note.finish], task.decoration);
                  BW.write(String.format(" %d ", target));
                  break;
                case RelativePosition:
                  if (target != -1)
                    BW.write(String.format(" %d ", getRP(task.states[note.finish][source], task.states[note.finish][target])));
                  else
                    BW.write(String.format(" %d ", getRP(task.states[note.finish][source], task.states[note.start][source])));
                  break;
                case XYZ:
                  BW.write(String.format(" %-5.2f %-5.2f %-5.2f ", task.states[note.finish][source][0],
                      task.states[note.finish][source][1], task.states[note.finish][source][2]));
                default:
                  System.err.println("We don't predict " + info);
                  return;
              }
            }
            // Compute conditioning variables
            for (Information info : Configuration.condition) {
              switch (info) {
                case CurrentWorld:
                  BW.write(getWorld(task.states[note.start]));
                  break;
                case NextWorld:
                  BW.write(getWorld(task.states[note.finish]));
                  break;
                case Utterance:
                  BW.write(unkUtterance(tokenize(utterance)));
                  break;
                default:
                  System.err.println("We don't condition on " + info);
                  return;
              }
            }
            BW.write("\n");
          }
        }
      }
    }
    System.out.println("Created " + filename);
  }

  public static strictfp void main(String[] args) throws Exception {
    Configuration.setConfiguration(args.length > 0 ? args[0] : "JSONReader/config.properties");

    ArrayList<Task> Train = readJSON(Configuration.training);
    ArrayList<Task> Test = readJSON(Configuration.testing);
    ArrayList<Task> Dev = readJSON(Configuration.development);
    //statistics(Train);

    computeVocabulary(Train);

    createMatrix(Train, "Train.mat");
    createMatrix(Test,  "Test.mat");
    createMatrix(Dev,   "Dev.mat");
  }
}
