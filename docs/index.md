
<div style="text-align:center; margin-bottom: 3em">
  <img alt="Hotvect Logo" src="https://hotvect.docs.zalando.net/hot_pepper.png" />
</div>

# What does it do?

Hotvect is an open source library for developing real-time and batch machine learning applications, especially personalized content re-rankers.
It supports the following tasks:

* Development of feature engineering code that can be shared across offline and online environment
* Integration of Machine Learning libraries like vowpal wabbit, catboost etc. into ML applications 
* Definition of ML enabled models and policies, and packaging them into a reusable, modular form that can easily be shared, combined, and deployed into production
* Offline testing and hyperparameter optimization of models and policies, as well as bookkeeping of test results
* Integration with Sagemaker for running offline tests and hyperparameter optimization at scale

# What does it not provide?
It does not provide:

* Machine Learning algorithms themselves (it is meant to be combined with existing machine learning libraries)
* Orchestration of machine learning pipelines (this needs to be provided through other frameworks like Airflow)
* Life-cycle management of models and policies (this is provided by the Experiment Management Service that supports Hotvect)
* Creation, management and execution of online experiments (this is also provided by the Experiment Management Service)
* Monitoring of ML applications and evaluation of online experiment results (this needs to be provided separately)

# Notes
Hotvect is designed to be library-agnostic - i.e. you can integrate it with any library. However, currently the library must be "playable" from a JVM process (for example through JNI, or through pure java implementations of ML algorithms like [h2o.ai's xgboost-predictor](https://github.com/h2oai/xgboost-predictor)). We plan to add inter-process integrations in the future.

The Feature Engineering is meant to be written in a JVM language (like Java, Kotlin, Scala etc.). The API for triggering various tasks like offline testing are provided as a python library.
