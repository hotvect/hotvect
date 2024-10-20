Hotvect
==========
A feature engineering and serving library for Vowpal Wabbit.

Hotvect allows you to:
1. Define data transformations (for feature engineering)
2. Generate training data for Vowpal Wabbit
3. Import the resulting model into a pure Java predictor

The same data transformation code will be used for training and prediction, making sure that 
there will be no discrepancies.

It has characteristics that work well with typical Vowpal Wabbit use cases.
1. Out-of-core: Processing happens without reading all data into memory, allowing processing of large amounts of data
2. Multi-threaded: Processing is multi-threaded, which shortens processing time
3. Efficient: Library is coded with efficiency in mind. Use of JVM makes it easy to write efficient feature transformations

Feature interaction is natively supported, although exploration of interaction feature requires a separate step.

