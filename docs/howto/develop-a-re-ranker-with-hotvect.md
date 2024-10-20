# How to: Develop a Re-ranker with Hotvect

## What is hotvect's scope?
Hotvect's main function is to (1) execute feature extraction/transformations, (2) sending that to a machine learning algorithm, and (3) returning the result. Optionally, business logic (like heuristics) and algorithmic logic (like Epsilon-Greedy, Softmax Exploring) can be implemented in it, too.  
Its focus is to (a) integrate a ML-based algorithm package seamlessly into a Java business application, and (b) in a way that is easy to test algorithms for correctness and system performance, so that algorithms can rapidly iterated upon. For this purpose, it provides other functionalities like backtesting, management of model parameters and hyperparameters, feature audits, integration with Sagemaker, etc.

## How do I define feature extraction in hotvect?
### Defining the `shared` and `action` object
When you re-rank a set of content, you have information that is `shared` across them (like the user_id, the context url, time of day etc.), and information that is specific to each `action`, like the name of the SKU, the price, the category etc. In hotvect, you can use an arbitrary Java object to represent these information. I.e. you should have one Java class that holds the `shared` information, and one Java class that holds the `action` information. The re-ranker essentially receives one instance of the `shared` object, and a list of `action` objects which it re-ranks.  

One could think of another category of information `slot`, which describes information about the possible places it would be displayed (e.g. which row it is, what the size of the image will be and so on). Currently, this information is not supported (although you could shoehorn it into the `shared` object via a List or a Map, for example).

There is no restriction about what these objects are - as long as they are Java objects, they will work. That said, you probably want to use objects that are easy to serialize and deserialize, like a Java POJO generated for a serialization framework (like Jackson, Protobuf etc.).  

### Hyperparameters and parameters
Aside from the `shared` and `action` objects, hotvect algorithms also has access to `hyperparameters` and `parameters` when calculating features. `hyperparameters` is essentially a JSON file to which you can write any parameters you want to use in the algorithm. However, importantly, it cannot be very large, and it stays the same for the lifetime of the algorithm version.  
`parameters` on the other hand is some arbitrary binary data that you can store against a name. Unlike `hyperparameters`, it can be any data, it can be large, and it's meant to be updated regularly (e.g. via a daily or hourly batch job).  
The most obvious use case for `parameters` is to store the model parameters of a machine learning algorithm. However, you can also use it to store other information, like a lookup table, or pre-calculated feature values.

### Defining the transformations
Ultimately, hotvect takes the `shared` object and a list of `action` (and whatever object that was derived from `hyperparameters` and `parameters`) and transforms them into a form that can be sent to machine learning models, which are also managed by hotvect. It then re-ranks the list of `action` and returns the result.  

Most ML algorithms accepts a type of dataframe as its input, and hence, hotvect also has an internal representation that is similar to a dataframe. However, unlike most dataframe implementation, it is row-oriented. This representation is called `NamespaceRecord`.  

To define features, you declare the name of the feature (`namespace`), and the function that should be used to calculate the feature value from the `shared` and `action` objects.  

#### The `NamespaceRecord`
`NamespaceRecord` is essentially a `Map<Namespace, Object>`, where the key is the name of the "column", and the value is the feature value. A list of `NamespaceRecord`s is very similar to a dataframe.

When you calculate features, it is important to be able to reuse calculations, as multiple features might depend on the same, expensive calculation. For example, you might want to calculate "the number of followers of a user from country X", and then use that value in multiple features. To enable this, `NamespaceRecord` accepts any value type. I.e. you can store any Java object as a value, and reuse it (just like you might do with a dataframe). 

However, not all Java objects can be sent to the ML algorithm as features. Exactly what type is supported depends on the ML algorithm, but for example, the CatBoost integration supports the following types:
```
 - Categorical (String)
 - Numerical (Double)
 - Text (Array of String)
 - Embedding (Array of Double)
```

To support this dual use (one for "caching" arbitrary calculation, and one for sending actual ML feature value), there are two types of `Namespace`. The "regular" `Namespace`, and the `FeatureNamespace` which is a subclass of `Namespace`. Only columns of type `FeatureNamespace` can be used as ML features, and you must declare what type of feature it holds (categorical, numerical, text etc.). Consequently, a single `FeatureNamespace` can only hold one type of feature value.

For performance reasons, the `Namespace` object must always be a singleton. You can either have an enum that implements the `Namespace` or `FeatureNamespace`, or use the `CompoundNamespace` class to create a "compound" `Namespace` out of enum based `Namespace`. The latter is useful if you want to have many features that are similar. 
For example, if you wanted to have features like below:
```
DE_CH_brand_matching_ctr,
FR_BE_brand_matching_ctr,
DE_CH_brand_matching_cvr,
FR_BE_brand_matching_cvr,
FR_BE_category_matching_cvr,
...
```

Instead of having to write out each enum, you can define 3 enums `Region{DE_CH, FR_BE}`, `Attribute{brand}`, `Metric{matching_ctr, matching_cvr}`and use the `CompoundNamespace` to create the "concatenated" `Namespace` objects to have all combinations. This way, you can dynamically generate features instead of having to hardcode them into the source code.  

When you create compound `FeatureNamespace` objects, a feature value type (like `categorical`, `numerical`, `text` etc.) is assigned the first time it is retrieved. After that its feature value type cannot be changed.

> **Why do `Namespace` need to be singletones?** In our use case, response time is critically important. Due to how often they are accessed, test revealed that only the EnumMap and IdentityHashMap can provide the necessary performance. Other objects, like interned strings were considered, but string interning is actually very expensive & it's hard to tell if a given string had been interned. With the singleton-via-a-factory approach, we can prevent and detect bugs to some degree.

#### Lazyness, memoization and parallalization
To build a good recommendation service, it is critical to process a lot of data within a short time. To achieve this, hotvect uses lazyness, memoization and parallelization.  

 - Lazyness: Hotvect starts from a feature that was requested, and calculates its dependency on-demand (i.e. when it is requested). This way, it can avoid calculations that are not needed. This is important as features can be turned on and off via the hyperparameter.
 - Memoization: Whenever a calculation depends on the same calculation, the result is automatically reused if "caching" is enabled for that calculation. Due to the small but nevertheless non-zero overhead of caching, it is not always advisable to use caching. By default, all calculations that only depend on `shared` object are cached, while others are not. This can be changed programmatically.
 - Parallelization: Hotvect parallelizes the calculation of feature values as well as the subsequent ML inference. This is done through the use of ForkJoin framework.

These have however, some consequence on the API as well as the implementation of feature extraction code.

 - `shared` and `action` objects, as well as calculation results in `NamespaceRecord` must not be mutated.
 - Functions must be thread-safe and idempotent (unless you know what you are doing)
 - You cannot directly read values out from the `NamespaceRecord` being created (instead, you have to request the value through the `Memoized` context object interface)

#### Memoization interface
You can use hotvect without using its memoization interface, but for a lot of use case it may be necessary to meet the performance requirements. To use memoization, use the `MemoizingRankingTransformer`. This transformer can be built through its Builder interface. It will automatically convert the incoming `RankingRequest`s into a `MemoizedRankingRequest` which provides a context object through which you can request calculation results.

Here are some example usages:

```java
/**
 * "UserContext" object is the shared object, and the "Ad" is the action object.
 * As stated above, this can be any java object.
 */
public class CTRModelTransformerFactory implements RankingTransformerFactory<UserContext, Ad> {
    
    /**
     * This method is called by the framework to obtain a transformer, which is shared across threads/requests.
     * @param hyperparameters The hyperparameters that are passed to the algorithm.
     * @param parameters The parameters that are passed to the algorithm, like feature stores.
     * @param algorithmDependencies Algorithms can use other algorithms as dependencies (e.g. for stacking).
     */
    @Override
    public RankingTransformer<UserContext, Ad> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies) {
        MemoizingRankingTransformer.MemoizingRankingTransformerBuilder<UserContext, Ad> builder = MemoizingRankingTransformer.builder();
        
        // Here we register a function that yields the brand ID from the action object
        // The result will be stored under the SKUAttribute.brand namespace
        builder.withActionTransformation(SkuAttribute.brand, new BrandExtractor());
        
        // A neat trick is to define the extractor function in the enum itself, like this:
        builder.withActionTransformation(SkuAttribute.category, SkuAttribute.category.getExtractor());
        
        // Which also allows you to register the extractors in a loop, like this:
        for(SkuAttribute skuAttribute : SkuAttribute.values()) {
            builder.withActionTransformation(skuAttribute, skuAttribute.getExtractor());
        }
        
        // Now let's declare extractors for the user's interaction history, which also contains brands
        // For all user behavior types
        for (UserBehaviorType userBehaviorType : UserBehaviorType.values()) {
            // For all SKU attributes 
            for (SkuAttribute skuAttribute : SkuAttribute.values()) {
                // Obtain the special namespace object that e.g. represents "add2cart_brand"
                Namespace namespace = CompoundNamespace.getNamespace(userBehaviorType, skuAttribute);
                
                // For each namespace, you register a function that should be called to extract the feature value
                // In this case, this function will operate on the shared object to yield a string array
                // representing the brands that the user had added to cart
                builder.withSharedTransformations(namespace, new UserBehaviorAttributeExtractor(userBehaviorType, skuAttribute));
            }
        }
        
        // Now, for each combination, we define the "MatchCount" feature
        // For all user behavior types
        for (UserBehaviorType userBehaviorType : UserBehaviorType.values()) {
            // For all SKU attributes that we can extract from a behavior
            for (SkuAttribute skuAttribute : SkuAttribute.values()) {
                // Constructing feature namespace with UserBehaviorType, SKUAttribute, and NumericalAggregationType
                FeatureNamespace featureNamespace = CompoundNamespace.getFeatureNamespace(CatBoostFeatureType.NUMERIC, userBehaviorType, skuAttribute, NumericalAggregationType.match_count);
                
                // This is the column that contains the SKU attribute of the action being considered
                Namespace probeNamespace = CompoundNamespace.getNamespace(skuAttribute);
                // This is the column that contains the SKU attribute of the user's interaction history
                Namespace galleryNamespace = CompoundNamespace.getNamespace(userBehaviorType, skuAttribute);
                
                // Use a match count function parameterized by the probe and gallery namespaces
                builder.withInteractionTransformation(featureNamespace, new MatchCount<>(probeNamespace, galleryNamespace));
            }
        }
        
        // Note that by defining a higher order function that returns the extractor function in the enum themselves,
        // you could have defined the above in a loop as well, further reducing redundant code.

        return builder.build();
    }
}
```

The above illustrates the definition of the transformer. Let's look at the `MatchCount` class.
```java
/**
 * MemoizedInteractionTransformation is a memoized BiFunction that takes shared and action object as input, and resturns
 * a value, in this case, a double. This can be a numerical feature, but also be used to calculate other features 
 * (like match_count_over_total).
 * @param <SHARED> The shared object type.
 * @param <ACTION> The action object type.
 * @param <T> The type of elements in the probe and gallery array.
 */
public class MatchCount<SHARED, ACTION, T> implements MemoizedInteractionTransformation<SHARED, ACTION, Double> {

    // The "probe" is generally the attribute of the action being considered
    private final Namespace probeId;
    // The "gallery" is generally the corresponding attribute of past user interactions, as an array 
    private final Namespace galleryId;

    public MatchCount(Namespace probeId, Namespace galleryId) {
        this.probeId = probeId;
        this.galleryId = galleryId;
    }

    /**
     * This is called by the framework, to obtain the result.
     * @param memoizedInteraction The is ultimately derived from the re-ranking request, and has the context to perform memoization.
     * @return The count of matches between the probe and gallery elements.
     */
    @Override
    public Double apply(MemoizedInteraction<SHARED, ACTION> memoizedInteraction) {
        T probe = memoizedInteraction.computeIfAbsent(probeId);
        T[] gallery = memoizedInteraction.getShared().computeIfAbsent(galleryId);
        if (probe == null || gallery == null || gallery.length == 0) {
            // null is used to indicate missing features (or missing values)
            return null;
        }

        return matchCount(probe, gallery);
    }

    private Double matchCount(T probe, T[] gallery) {
        int count = 0;
        for (T g : gallery) {
            if (probe.equals(g)) {
                count++;
            }
        }
        return (double) count;
    }
}
```

A similar code in python would look like this (although the java version above has more generalizations):
```python
df = pd.DataFrame({
    'action_sku_brand': ['BrandA', 'BrandB', 'BrandC', 'BrandD', 'BrandE'],
    'added_to_cart_brand': [['BrandA', 'BrandE', 'BrandA'], ['BrandB', 'BrandC'], ['BrandG', 'BrandH'], ['BrandD'], ['BrandX']],
    'clicked_brand': [['BrandE', 'BrandF'], ['BrandB', 'BrandB'], ['BrandC', 'BrandG'], [], ['BrandE', 'BrandZ']],
    'ordered_brand': [['BrandA'], [], ['BrandC'], ['BrandD'], ['BrandE']]
})

# Parameterized function to count matches
def count_brand_matches(df, action_col, gallery_col, new_col_name):
    def count_matches(row):
        action_brand = row[action_col]
        gallery_brands = row[gallery_col]
        return gallery_brands.count(action_brand)
    
    df[new_col_name] = df.apply(count_matches, axis=1)

# Compute match counts for different user behaviors
count_brand_matches(df, 'action_sku_brand', 'added_to_cart_brand', 'add2cart_brand_match_count')
count_brand_matches(df, 'action_sku_brand', 'clicked_brand', 'click_brand_match_count')
count_brand_matches(df, 'action_sku_brand', 'ordered_brand', 'order_brand_match_count')

print(df)
```

Let's also look at some of the Namespace Enums
```java
public enum SkuAttribute implements Namespace {
    brand(
        action -> action.getBrand()
    ),
    category(
        action -> action.getCategory()
    );

    // This is where we store the extractor function
    // In this case we always extract a String, but you can mix output types as well
    private final MemoizedActionTransformation<Ad, String> extractFromAction;

    SkuAttribute(MemoizedActionTransformation<Ad, String> extractor) {
        this.extractFromAction = extractor;
    }
    
    // This function can be used in a loop to register the extractor for this enum value
    public MemoizedActionTransformation<Ad, String> getExtractor() {
        return this.extractFromAction;
    }
}
```

#### Common mistakes
 - Mutating the `shared` or `action` objects, or the calculation result for a `Namespace`: These objects are shared across threads and actions in unpredictable order, and hence, must not be mutated. Especially be careful if the values are collections or arrays etc. If you need to mutate them, you must make a copy of them first.
 - Implementing your own caching: Hotvect automatically stores the calculation result in a variable for caching, so you don't need to do things like ``` if (this.result == null) this.result = calculate()```.
 - Having unnecessary intermediate columns (namespaces): While namespaces (columns) are meant to be cheap, they are still a lot slower than native method dispatch. Hence, use them if you need to hold a feature, or if you need to reuse a calculation. It's ok to use columns for convenience, but if you can avoid it, it makes it more performant.
 - Using `Stream`s: Believe it or not, Java's `Stream` are generally a lot slower than a simple loop. Unless you are crunching a very large stream, avoid them (especially the parallel stream).
 - Turning on caching blindly (on action and interaction transformations): Caching is only beneficial if the computation is sufficiently heavy. When the calculation is too light, it can be detrimental to performance. Therefore, only turn on caching if you confirmed the performance gain of doing so.
 - Not caching namespace objects: Obtaining the namespace object at inference time is expensive and even unsafe, as the CompoundNamespace class is not thread-safe. Always obtain the namespace object at the time the transformer is being created, and store them into a field for further use.
 - Avoiding compound namespaces: There is no performance gain for using a enum based namespace over a compound namespace, because the underlying map is an IdentityHashMap.
 - Looking for ways to configure the thread pool used for parallelization: Don't bother, it's not worth it.

#### Tricks and tips
##### Use "plain" namespaces to make feature names clearer
A namespace doesn't always have to have a function associated with it. You can also have a namespace that is only used as a prefix or suffix (or in the middle section, for that matter). For example, if you prefer the name "candidate_brand" instead of just "brand" for the brand feature of the candidate SKU above, you could just do:
```java
FeatureNamdspace ns = CompoundNamespace.getFeatureNamespace(CatBoostFeatureType.CATEGORICAL, FeaturePrefix.candidate, SkuAttribute.brand);
builder.withActionTransformation(ns, new BrandExtractor());
```
Instead of:
```java
builder.withActionTransformation(SkuAttribute.brand, new BrandExtractor());
```
And your feature will have the name `candidate_brand` instead of `brand`. As described above, as long as you obtain the namespace objects at the time of building the transformer and store them into fields, there is no performance impact of using more namespace segments.

This allows you to define multiple extractors for the same attribute, for example, and you can declare multiple features that extract the same attribute from different places, for example. E.g.:
```java
builder.withInteractionTransformation(FeaturePrefix.candidate, SkuAttribute.brand, new BrandExtractor());
```

##### Using a thread local cache (or even a global cache)
In some situations, it might be beneficial to use a thread local cache. For example, if all the SKUs in the request happens to have the same brand, the feature "add2cart_brand_match_count" would be the same for all SKUs. Memoization currently doesn't help with this kind of situation, as you can't memoize on an arbitrary arguments.
In this situation, you can use a thread local cache that stores the last calculation, and if the argument happens the same, return the result. For example:
```java
public class MatchCount<SHARED, ACTION, T> implements MemoizedInteractionTransformation<SHARED, ACTION, Double> {
    private final boolean useThreadLocalCache;

    private final Namespace probeId;
    private final Namespace galleryId;
    private class CacheEntry {
        final T probe;
        final T[] gallery;
        final Double result;

        private CacheEntry(T probe, T[] gallery, Double result) {
            this.probe = probe;
            this.gallery = gallery;
            this.result = result;
        }
    }

    private final ThreadLocal<CacheEntry> cache = ThreadLocal.withInitial(() -> new CacheEntry(null, null, null));


    public MatchCount(Namespace probeId, Namespace galleryId) {
        this.probeId = probeId;
        this.galleryId = galleryId;
        this.useThreadLocalCache = false;
    }

    public MatchCount(Namespace probeId, Namespace galleryId, boolean useThreadLocalCache) {
        this.probeId = probeId;
        this.galleryId = galleryId;
        this.useThreadLocalCache = useThreadLocalCache;
    }

    @Override
    public Double apply(MemoizedInteraction<SHARED, ACTION> memoizedInteraction) {
        T probe = memoizedInteraction.computeIfAbsent(probeId);
        T[] gallery = memoizedInteraction.getShared().computeIfAbsent(galleryId);
        if (probe == null || gallery == null || gallery.length == 0) {
            return null;
        }

        if(useThreadLocalCache){
            CacheEntry cached = cache.get();
            if (probe.equals(cached.probe) && cached.gallery == gallery) {
                return cached.result;
            }
        }

        Double result = matchCount(probe, gallery);
        if(useThreadLocalCache){
            cache.set(new CacheEntry(probe, gallery, result));
        }
        return result;
    }

    private Double matchCount(T probe, T[] gallery) {
        int c = 0;
        for (T g : gallery) {
            if (probe.equals(g)) {
                c++;
            }
        }
        return (double) c;
    }
}
```

Transformation functions are instantiated as singletons and shared across threads/requests, and hence the thread local cache can be stored in an instance field.

You could also use a global cache, but be careful with this, as it can lead to memory leaks and/or hurt performance. Caches that are shared across threads have relatively high costs and only make sense in rather extreme cases.

#### How to debug/profile it?
To inspect if there are issues in the calculated feature value: Use the "feature-audit" functionality. Especially for regression testing, you can use the "compare-audit" script to see only the feature values that have changed between versions.

To debug issues: Without a debugger, it will be difficult to debug issues. Refer to [How to debug feature engineering](./debug-feature-engineering.md) to use a debugger.

To profile the feature engineering to optimize its performance: it can be helpful to turn-off the parallelization feature (so that the profiling results are easier to interpret). You can do this from the hyperparameter.

#### How to unit test it?
WIP
