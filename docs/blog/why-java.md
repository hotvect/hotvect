# Why hotvect uses Java for feature extraction

It's difficult to do something without knowing why you have to do it. So let's talk about why hotvect makes you use Java (or more precisely, JVM languages) to define your feature extraction, heuristics, business logic, etc. when developing algorithms. In other words, why not Python? There are three reasons, and only one of them is a blocker.

## The Blocker: Performance
Simply put, Python isn’t a feasible choice to perform processing like feature extraction in a large-scale real-time recommender system context. You might find this surprising, since there are plenty of real-time recommender systems that are written in Python.

This gap stems from two factors. First, many real-time recommender systems depend on batch features and near real-time features rather than real-time features. Batch Features are periodically precomputed using batch processes like Spark. While they are easy to implement and scale, they tend to become stale. Near Real-Time Features are updated asynchronously via streaming frameworks like Flink. They offer fresher data than batch features. Real-Time Features, on the other hand are computed dynamically during prediction. It ensures the freshest data possible, and importantly, can depend on information that is only available at request time.

Apart from the obvious point that near real-time features introduces additional latency, real-time features present an often overlooked advantage: because they can use information that is only available at request time, they can significantly reduce the amount of computation and the payload size. Lack of this possibility is often a significant barrier to feeding more data into the algorithms. Another important benefit of real-time feature is the flexibility it creates. Changing the calculation in batch or near real-time features often requires separate reprocessing of the data, which can be time-consuming. Real-time features, on the other hand, can be changed on the fly. Many frameworks like Hopsworks, MLFlow etc. allows definition of real-time features (aka "on-demand features") using python UDFs, but the use of python severely limits the transformation that can be done.

Secondly, Python is often uses as Domain Specific Language (DSL), which ultimately translates to code written in other languages (e.g., C++, Rust, or Java) to achieve acceptable performance. While this approach works for many standard transformations (like counting, applying regexes, vector operations), many useful transformations lead to execution of pure Python code either intentionally or accidentally. This quickly turns into severe performance bottlenecks. This issue is further compounded by Python’s poor concurrency support, which is particularly problematic in a high-load & latency sensitive applications.

Let's take a concrete example. The following Java code takes about 30 seconds to run if not parallelized. When parallelized with 2 threads, it runs in about 18 seconds. This is about what we'd expect.
```java
Map.Entry<String, Integer> benchmark(String inputJson) throws Exception {
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    ObjectMapper objectMapper = new ObjectMapper();
    String[] fields = {"orders", "views", "add2carts"};

    final ConcurrentMap<String, Integer> counters = new ConcurrentHashMap<>();

    Callable<Void> task = () -> {
        int iterationCount = 0;
        while (iterationCount < 500000) {
            JsonNode parsedJson = objectMapper.readTree(inputJson);
            for (String field : fields) {
                JsonNode actions = parsedJson.get(field).get("events");
                for (JsonNode record : actions) {
                    String productId = record.get("product").get("product_id").textValue();
                    for (int i = 0; i < 8; i++) {
                        String keySegment = productId.substring(i, 10);
                        counters.merge(keySegment, 1, Math::addExact);
                    }
                }
            }
            iterationCount++;
        }
        return null;
    };

    executorService.submit(task);
    executorService.submit(task);
    executorService.shutdown();
    executorService.awaitTermination(10, TimeUnit.MINUTES);

    return counters.entrySet().stream()
                   .sorted(Comparator.comparingInt(e -> e.getValue()))
                   .findFirst()
                   .get();
}
```

Meanwhile, the following roughly equivalent Python code takes 60–80 seconds to run. And if we attempt to parallelize it in a similar way, it appears to get stuck for a very long time.

```python
def countItems(inputJson, counters):
    iterationCount = 0
    while iterationCount < 500000:
        parsedJson = json.loads(inputJson)
        for field in ["orders", "views", "add2carts"]:
            actions = parsedJson[field]["events"]
            for record in actions:
                productId = record["product"]["product_id"]
                for i in range(8):
                    keySegment = productId[i:10]
                    counters[keySegment] = counters.get(keySegment, 0) + 1
        iterationCount += 1

def benchmark():
    manager = multiprocessing.Manager()
    counters = manager.dict()
    jobs = []
    for _ in range(2):
        p = multiprocessing.Process(target=countItems, args=(inputJson, counters))
        jobs.append(p)
        p.start()
    for proc in jobs:
        proc.join()

    result = sorted(counters.items(), key=lambda x: x[1])[0]
    print(result)
```

You can of course argue that there are much better ways to optimize code in Python. However, the point is that it's very easy to write Python code that performs very poorly. JVM languages are much more forgiving in this regard. 

Another supporting evidence is the fact that practically all data processing frameworks or databases are written in languages like Java, C, Rust, Go etc. Try asking LLMs to come up with “10 most popular data processing systems”:

```
1. Apache Hadoop – Java
2. Apache Spark – Java, Scala
3. Apache Kafka – Java
4. Apache Flink – Java, Scala
5. Apache Beam – Java, Python, Go
6. Apache Nifi – Java
7. Apache Druid – Java
8. Apache Hive – Java
9. Apache Storm – Java, Scala
10. Apache Pulsar – Java
```

Or “10 most popular databases”:
```
1. MySQL – C, C++
2. PostgreSQL – C
3. MongoDB – C++
4. MariaDB – C, C++
5. Redis – C
6. Cassandra – Java
7. Elasticsearch – Java
8. Neo4j – Java
9. SQLite – C
10. ClickHouse – C++
```

We can also observe the phenomena of "leaking" in many real-life recommender systems. In many large scale recommender systems, one can find feature extraction logic that has "leaked" out of the recommender system (like a Sagemaker inference endpoint) into the business application (coded in languages like Go, Java, C# etc.). For example, they perform extraction of fields from JSON payloads, counting and other aggregations, sorting and truncation etc. This is very harmful to a recommender system, as it makes difficult to A/B test changes to this logic. It's not uncommon to see these logics to remain unchanged long after a recommender system was introduced, even if there are good improvement ideas.

Many companies, such as Meta are investing into frameworks that allow applied scientists to define feature transformations in Python, which are then translated into performant machine code. While a lot of progress has been made, it has not yet succeeded into covering all common use cases reliably.

## Reducing the Impedance Mismatch Between Business Logic and ML

Another major reason for using JVM languages is the seamless integration with your existing business logic. Many organizations use JVM languages for their business applications, with which the recommender system integrates. There might be rich domain objects for concepts like Article, User, or Advertisement campaigns, or other business logic available as JVM libraries. Writing feature extraction in the same language avoids the need to rebuild, export, or duplicate these models or logic in Python. By sharing the code with the business application, you preserve a single source of truth for business and ML apps. This consistency makes it easier to maintain alignment with the application’s evolving requirements and quickly respond to new feature requests or changes while avoiding subtle bugs.

Hotvect also creates a smooth transition between the object-oriented world and the dataframe world. While Python is an object-oriented language, in the ML world we primarily work with dataframes, especially when Python is used as a DSL. Therefore, a common pattern is to first translate objects into dataframes and then transform the dataframe. However, this can often feel like shoehorning. With Hotvect, features can be extracted from objects and then put into a dataframe, making this process smoother.

## Deeper Integration with Infrastructure and the Business Side
When your business application and ML code share the same platform, certain integration patterns become easier. Typically, you might call out to an external ML service, get back predictions, and continue processing. However, you might want to have more complex interactions. For example, say you want to access a database, create a query using ML components, then query another database using the output, feed it into multiple ML models, and afterward apply more business logic or feed it into yet another ML models. Such complex interactions between models, databases and business logic are difficult to achieve in a microservice configuration. Allowing intra-process communication opens up a lot of possibilities here.

This, however, does not mean the ML libraries must be JVM-based. A lot of ML libraries like CatBoost, XGBoost, and TensorFlow offer efficient ways to run inference on JVM processes. Even if the ML library does not directly support running on the JVM, it can be used via a sidecar pattern (where a small companion process is managed by the main process and communicates through various IPCs) or by calling out to a separate endpoint.

This setup also simplifies A/B testing of infrastructure changes. The algorithm itself can specify which database or service are called, with what parameters etc. Instead of having a separate layer for A/B testing infra changes, the algorithm and the infra changes can be A/B tested at the same time.
