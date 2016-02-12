# MongoDB Batch Source


Description
-----------
Reads documents from a MongoDB collection and converts each document into a StructuredRecord with the help
of a specified schema. The user can optionally provide input query, input fields, and splitter classes.


Configuration
-------------
**connectionString:** MongoDB connection string. Example: `mongodb://localhost:27017/analytics.users` 
[Reference](http://docs.mongodb.org/manual/reference/connection-string)

**schema:** Specifies the schema of the documents.

**authConnectionString:** Auxiliary MongoDB connection string to authenticate against when constructing splits.

**inputQuery:** Optionally filter the input collection with a query. This query must be represented in JSON format
and use the MongoDB extended-JSON format to represent non-native JSON data types.

**inputFields:** Projection document that can limit the fields that appear in each document. 
If no projection document is provided, all fields will be read.

**splitterClass:** The name of the Splitter class to use. If left empty, the MongoDB Hadoop Connector will attempt
to make a best-guess as to which Splitter to use. The Hadoop connector provides these Splitters:

  - `com.mongodb.hadoop.splitter.StandaloneMongoSplitter`
  - `com.mongodb.hadoop.splitter.ShardMongoSplitter`
  - `com.mongodb.hadoop.splitter.ShardChunkMongoSplitter`
  - `com.mongodb.hadoop.splitter.MultiMongoCollectionSplitter`