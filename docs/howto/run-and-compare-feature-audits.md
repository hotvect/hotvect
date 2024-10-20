# How to: Run and compare feature audits

## What are feature audits?
Feature audits are a way to output feature values as readable json files. It outputs the same feature values as encoding, but in a human readable format. It is useful for debugging and comparing feature values between different algorithm versions.

For example, your algorithm might produce the following encoded training file:
```text
0.0\t4ec4fd\tTechnology\t32\t452\t0.12
2.0\t1e56fa\tTechnology Lifestyle\t3252\t12\t0.75
```
This file is not very readable. With the audit function, you can produce the same value in json format as follows:

```json
{
  "example_id": "4ecceb1e-64e4-41f1-TEST-000000000000",
  "actions": [
    {
      "reward": 0.0,
      "features": {
        "post_id": "4ec4fd",
        "post_category": [
          "Technology"
        ],
        "user_followers_count": 32,
        "post_like_count": 452,
        "post_sentiment_score": 0.12
      }
    },
    {
      "reward": 2.0,
      "features": {
        "post_id": "1e56fa",
        "post_category": [
          "Technology",
          "Lifestyle"
        ],
        "user_followers_count": 3252,
        "post_like_count": 12,
        "post_sentiment_score": 0.75
      }
    }
  ]
}
```

## How to run feature audits
The easiest way to run feature audits is to use the `run-audit` script as follows:

```bash
run-audit \
--algorithm_jar ~/.m2/repository/com/yourcompany/your-algorithm/1.2.3/your-algorithm-1.2.3.jar \
--algorithm_name your-algorithm-click-model \
--metadata_path audit.metadata.1.2.3.json \
--source_path training_data/dt=2024-01-06 \
--dest_path audit.1.2.3.jsonl \
--samples 2
```

The script will audit `--samples` from the source path. The processing is ordered (the youngest line from the youngest file name is processed first). Hence, as long as you use the same source_path and the same `--samples`, the same samples will be audited in a reproducible way.

If the encoding requires encode parameter files, you can specify them using the `--parameters` option.


If you need to override the algorithm definition, or specify different JVM options, you can ran the java command directly as follows:

```bash
java -cp ~/.m2/repository/com/hotvect/hotvect-offline-util/x.y.z/hotvect-offline-util-x.y.z-jar-with-dependencies.jar -Xmx32g -XX:+ExitOnOutOfMemoryError \
com.hotvect.offlineutils.commandline.Main \
--algorithm-jar ~/.m2/repository/com/yourcompany/your-algorithm/1.2.3/your-algorithm-1.2.3.jar 
--algorithm-definition custom.algorithm.definition.json \
--meta-data audit_metadata.1.2.3.json \
--audit \
--source training_data \
--dest audit.1.2.3.jsonl
--parameters encode.parameters.file.zip
```

## How to compare feature audits
To compare audit outputs from different algorithm versions, use the compare-audits script:

```bash
compare-audits audit.1.2.3.jsonl audit.1.2.4.jsonl | jq .
```

If there are differences between the two audit files, the script will output the differences as json. Otherwise, it will output an empty json object `{}`.
Additionally, it will produce three files for convenience:
 - `diff.<file1>-<file2>.line=<n>.json`: The difference as pretty printed json file (same as the script output)
 - `audit.<file1>.line=<n>.json`: The feature values from file1, pretty printed
 - `audit.<file2>.line=<n>.json`: The feature values from file2, also pretty printed
Note that the output is only generated for the first line that is different. Also, when the feature value is an array of integers or strings, the order of the elements in the array is not considered. They are considered equal if they have the same elements same number of times.


Example output:
```json
{
  "actions": [
    {
      "features": {
        "user_followers_count": {
          "audit.1.2.3": 5.0,
          "audit.1.2.4": 3.0
        },
        "post_category": {
          "audit.1.2.3": [
            "Technology"
          ],
          "audit.1.2.4": [
            "Lifestyle"
          ]
        }
      }
    }
  ]
}
```