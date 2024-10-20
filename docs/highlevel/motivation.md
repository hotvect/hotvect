# Key differences with other similar frameworks

## Integration model with business applications

### Currently used integration model in the industry
Many Machine Learning applications, especially those that provide a real-time API have adapted the following design pattern:

- There is a **Java application (A)** that handles business rules and calling of dependencies like feature stores
- There is a **Python application (B)** (like a Sagemaker inference endpoint) that receives request from said Java application

From the Applied Scientists' point of view, component **B** would be the service they contribute/deploy to, and component **A** provides the infrastructure through which the input to **B** and the output from **B** travels. Hence, A/B testing also focused on A/B testing component **B**, while component **A** is intended as general infrastructure that does not vary across algorithms, and hence usually not a target to be A/B tested.

### Problems with the current integration model
This design philosophy lead to the following phenomena:

1. Attempts are made to keep the processing that happens in component **A** "general", i.e. agnostic to individual algorithms. For example, transformation of features, especially lossy transformations are kept to a minimum.
2. It is usually cumbersome to A/B test changes to component **A**, and such tests are only rarely done.

This situation would not pose a problem, if component **A** can be kept general. In reality, the following technical constraints often make this untenable:

1. *Size of payload*: In order to keep the processing in component **A** general, one has to keep feature data raw - for example, one can't aggregate it to reduce its size. However, certain features, like a users historical event data, statistics about each candidate contents etc. can be very large when they are raw. This often means that component **A** is forced to perform lossy transformation (i.e. de facto feature engineering) to keep the payload small enough for real-time use cases
2. *Computational Efficiency*: Python is often the preferred language for component **B**, but it is sometimes challenging to code the necessary feature transformation in a way that it is performant enough in python. This is especially true when parallelization that benefit from having shared states have to be involved.

Feature stores that facilitate asynchronous feature transformation/aggregation has been used to address this point. However, feature transformations that depends on information that is only available at request time cannot be supported this way.

# New integration model suggested by hotvect
Hotvect proposes a new integration model, in which:
1. Applied Scientists deploy code (in the form of dynamically loaded jar) onto component **A**, instead of deploying to a remote endpoint **B**. This allows component **A** to benefit from Intra-Process-Communication which mitigates the limitation that comes with large payloads.
2. Applied Scientists develop feature engineering using the JVM instead of python, which makes efficient feature engineering code easier to write (partly because the team can draw on high-load low-latency expertise of the team developing the component **A**)

While we do not support it yet, once the feature engineering is performed (and the payload is sufficiently small), it can then be sent to a remote endpoint **B** - meaning that, the core machine learning algorithm does not need to be executable on the java process of component **A**.

This change in Applied Scientists' contribution interface (from a remote, python based endpoint to a BYOA-style jar) also facilitates experimentation of other parts of component **A**. For example, things like whether to call an additional feature store (that e.g. could increase latency) becomes part of the policy to be tested, and can readily be A/B tested just like any other changes to the algorithm, like changes in the training hyperparameters.
