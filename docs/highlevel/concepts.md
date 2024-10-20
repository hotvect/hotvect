Concepts and terminologies
========


# The "Algorithm"
In hotvect, the term "Algorithm" refers to models and policies that are to be managed by the hotvect framework. Similar to how Spring Beans or Enterprise Java Beans (EJB) are managed by the Spring container or EJB containers, "Algorithm"s in hotvect are managed by the hotvect container. For example, the container manages their lifecycle, injects dependencies, and replaces them when new parameters are available.

Technically, almost anything could be an "Algorithm", but the current, natively supported types are:

 - `Rankers`: Policies that accept a set of possible content candidates and re-ranks them. Rankers often use models or "Scorers" inside
 - `Scorers`: Models that accept one, or multiple possible content candidates and scores them. Usually the scores are then used for re-ranking.

Multiple algorithms can be combined to form a "Composite Algorithm" (for example, a `Scorer` can be combined with a ranking policy to form a `Ranker`, or multiple `Scorer`s can be combined to create an ensemble model). We will cover this in detail later.

# Training-mode and inference-mode, offline-mode and online-mode
## Training-mode and inference-mode
All algorithms perform "inference" - that is, when they are asked to predict something or make a decision. Some algorithms (like heuristic based ones) only have inference mode, but most algorithms have also "training mode" in order to obtain the parameters to be used in "inference mode".

## Offline-mode and online-mode
Inference can be executed "online", i.e. in "real-time" e.g. on application servers as part of a real-time API. Or it can also be executed "offline", i.e. in "batch" e.g. as part of a data pipeline. Currently, hotvect does not support online training. Therefore, Training is always done offline.

## Algorithm package is shared across all modes
The "Algorithm" package - that is, its feature engineering code, hyperparameters, parameters (in case of inference mode) as well as any other supporting classes like codec is shared across offline and online. That is, an "Algorithm" can be written once and then be executed either using the hotvect offline container, or the hotvect online container. Similarly, data transformation code is shared across training mode and inference mode. This helps to eliminate offline-online discrepancies, especially in feature engineering.

Currently, the hotvect offline container does not support distributed execution (it's on our plans). It makes extensive use of parallelization, and is designed to run on larger machines and take advantage of multiple cores.

# The algorithm package
Algorithms are packaged into a special archive that self-sufficiently defines an algorithm. It consists of the following component:

 - **algorithm jar**: A jar that defines all data transformation code, such as how to calculate features, how to encode them and how to perform inferences
 - **algorithm definition JSON**: A JSON file that contains all configurable hyperparameters for the jar, such as which ranking strategy to use, which features to use, what hyperparameter to use during training and so on. It also contains other information that defines the algorithm, such as its data dependency, which container needs to be used for training and so on.
 - **parameter zip file**: A zip file that contains all necessary parameter to instantiate an algorithm that can perform its function. It is created during training, and is usually periodically updated while in production.

The generation of dataset itself is currently out-of-scope (the algorithm jar assumes the specified datasets already exists). In the future, we may expand the scope of hotvect to include processing during dataset generation.

The **algorithm definition JSON** is contained within the **algorithm jar**, so that only a jar is needed to "play" an algorithm. However, it is possible to supply a customized algorithm definition JSON to the container in order to override its content. This is meant for hyperparameter optimization.

## Dynamic loading of algorithm packages
The algorithm jar is meant to be dynamically loaded by the container (which could be e.g. a Spring Boot application, a Spark pipeline, or a console app). The hotvect container used for running the training job uses the same mechanism to load the algorithm jar dynamically (so as to minimize online/offline discrepancy).  \This is particularly handy for experimentation. A Spring Boot business application can be integrated with the Experimentation Management Service, which provides the application instructions about which algorithm to load, and which traffic to allocate to which algorithm. Thanks to the dynamic loading feature, these changes can be done without re-deploying or rebooting the business application. The entire lifecycle of algorithms - from deployment, experimentation, promotion to default algorithm, undeployment, deactivation can be managed through API requests to Experimentation Management Service. There is no need to redeploy components, change any routing configurations and so on. The Experimentation Management Service provides a client SDK which can be embedded into the business application, and performs these changes in the business application automatically.

## Algorithm jar artifacts
Each algorithm is not only a jar, but also a maven artifact. They thus have their `groupId`, `artifactId` and their `version`. It is therefore possible to deploy algorithm into maven repositories to combine them or share them across multiple applications.

For convenience during hyperparameter optimization, they can also have a "subversion" called `hyperparameter_version` for cases when their hyperparameters packaged within the jar is overriden. The `hyperparameter_version` identifier is concatenated to the maven `version` so that evaluation results etc. can be kept separate within the same `version`. However, since maven does not support such an identifier, when you release an algorithm to production/maven you cannot use the `hyperparameter_version`, and instead have to create a new `version`.

# The Experimentation Management Service
The Experimentation Management Service is not directly part of the hotvect framework (it can be used independently to execute e.g. non-ML related A/B tests). However, it was designed to work well with hotvect.

The Experimentation Management Service supports the following tasks:

* Registration of algorithms and bookkeeping of their statuses and parameter versions
* Lifecycle management of algorithms (activation, training, deactivation)
* Creation, management and bookkeeping of online experiments
* Randomization, traffic allocation including ramping up/ramping down
* Running of longitudinal negative control variants, forced assignments, exclusion rule of algorithm application (of specific campaigns, users etc.)
* Coordination of A/B tests across multiple "touchpoints" (aka "layers")

As described above, the Experimentation Management Service provides a client SDK library that can be embedded into business application to support propagation of experimentation instructions, randomization, logging of results, monitoring and so forth.

It also provides library functions that supports evaluation of online A/B test results. For more details, see its own documentation here.

# Sagemaker integration and Training Container
Hotvect training can be run on a local node, but it can also be scheduled on Sagemaker. For this purpose (and also to make dependency management easier), there are training containers that are compatible with Sagemaker. Unlike standard Sagemaker containers, training containers of hotvect perform feature engineering before feeding the result to the machine learning libraries like catboost, vowpal wabbit and the like.  

For this purpose, all training container requires java to be installed, and a reference to an algorithm jar to run. Hotvect provides a python sdk - somewhat similar to the one provided by Sagemaker - that can handle this process. It also provides entry points to schedule Sagemaker jobs, adaptor for Sagemaker's hyperparameter optimization feature, and also ways to retrieve the output from these jobs.
