# How to: Run User Defined Functions (UDF) on SageMaker

## What can you do with it?
Sometimes it's useful to run an arbitrary function over your data. For example, you might want to check certain statistics, or calculate alternative rewards for your test data to obtain non-standard evaluation metrics. You can of course do this using e.g. Spark. However, if your function is written in JVM languages/depend on domain artifacts used in your algorithms, it can be easier to run it using hotvect, as you do not have to upload/register your UDF. Also you can use the same interface as backtest to perform the task.

## Prerequisite knowledge
Running a function on top of a list of records is called `map`. In this case, each line will produce another, transformed line. However, you might want to skip a line, or produce more than one line for each input line. This is called `flatMap`. You can perform `map` using `flatmap` by always returning one line for each input line.  
Hotvect provides `flatmap`. That is, given a data path, it will apply the flatmap function line by line. The input is fed ordered - that is, starting from the first line of the earliest file name (alphabetically ordered) through the last line of the last file name. The output is written to a single file line by line. Hence, the signature of the flatmap UDF must be `def func(line: String): List[String]`.

## How to develop your flatmap function
In order to run the flatmap function using hotvect, you must implement the `com.hotvect.offlineutils.commandline.util.flatmap.FlatMapFunFactory` interface, from which you return your flatmap function of signature `Function<String, List<String>>`. The factory takes `Optional<JsonNode> hyperparameter` as an argument, which you can pass from the callsite to configure your flatmap function. It can of course be empty.

## How to run your flatmap function
### Running it locally
Here is an example command:
```java -cp ~/.m2/repository/com/hotvect/hotvect-offline-util/x.y.z/hotvect-offline-util-x.y.z-jar-with-dependencies.jar com.hotvect.offlineutils.commandline.util.flatmap.FlatMapFile --jars ./target/my-custom-udf-1.2.3.jar --flatmap-class org.myorg.rewards.MultiRewardsFactory --source ~/somedata/dt=2024-02-02 --dest test-output.jsonl ```

Apart from the output, it will also create a small json file (`metadata.json` by default) containing the metadata of the run.


### Running it on SageMaker
This does not work yet, unfortunately.
When it works, it will build the specified git reference, upload the udf jar to s3, and run the flatmap function on SageMaker on the specified dates.
```python
from hotvect.sagemaker_exp import run_remote_using_git_reference

def main():
    sagemaker_training_job_definition = {
        "TrainingJobName": "my-flatmap-job",
        "AlgorithmSpecification": {
            "TrainingInputMode": "FastFile",
            "TrainingImage": "some_training_image",
        },
        "InputDataConfig": [
            {
                "ChannelName": "my_data",
                "DataSource": {
                    "S3DataSource": {
                        "S3DataType": "S3Prefix",
                        "S3Uri": "s3://my_data/",
                    }
                },
                "InputMode": "FastFile",
            },
        ],
        "OutputDataConfig": {"S3OutputPath": "s3://my_output/"},
        "ResourceConfig": {
            "InstanceType": "ml.m5.2xlarge",
            "VolumeSizeInGB": 30,
            "InstanceCount": 1,
        },
        "EnableManagedSpotTraining": True,
        "StoppingCondition": {
            "MaxRuntimeInSeconds": 123,
            "MaxWaitTimeInSeconds": 123,
        },
        "RoleArn": "arn:aws:iam::my_role",
        "HyperParameters": {},
    }

    run_remote_using_git_reference(
        remote_work_dir="s3://my_emote_work_dir",
        local_work_dir="/Users/abcdef/my_local_work_dir",
        repo_url="git@myrepo:my-udf-repo.git",
        git_reference="123abcdef123",
        sagemaker_training_job_definition=sagemaker_training_job_definition,
        last_target_time=date(2024, 1, 1),
        number_of_runs=1,
        hyperparameters={"some_parameter": "123"},
    )
```