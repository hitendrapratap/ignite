/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

var apacheIgnite = require("apache-ignite");

var Ignition = apacheIgnite.Ignition;
var SqlQuery = apacheIgnite.SqlQuery;
var SqlFieldsQuery = apacheIgnite.SqlFieldsQuery;
var CacheEntry = apacheIgnite.CacheEntry;


/**
  * Cache queries example. This example demonstrates SQL queries over cache.
  * <p>
  * Start Ignite node with {@code examples/config/example-ignite.xml} configuration before running example.
  * <p>
  * Alternatively you can run ExampleJsNodeStartup which will
  * start node with {@code examples/config/example-ignite.xml} configuration.
  */
main = function() {
    /** Cache name. */
    var cacheName = "CacheQueryExample";

    /** Connect to node that started with {@code examples/config/js/example-js-cache.xml} configuration. */
    Ignition.start(['127.0.0.1:8000..9000'], null).then(function(ignite) {
        console.log(">>> Cache query example started.");

        // Create cache on server with cacheName.
        var cache = ignite.cache(cacheName);
        cacheQuery(ignite, cache);
    }).catch(function(err) {
        if (err !== null)
            console.log("Start remote node with config examples/config/example-ignite.xml.");
    });

    // Run query example.
    function cacheQuery(ignite, cache) {
        var entries = initializeEntries();

        // Initialize cache.
        cache.putAll(entries).then(function(){
            console.log(">>> Create cache for people.");

            //SQL clause which selects salaries based on range.
            var qry = new SqlQuery("salary > ? and salary <= ?");
            qry.setReturnType("JsonObject");

            // Set page size for query.
            qry.setPageSize(2);

            //Set salary range.
            qry.setArguments([0, 2000]);

            var fullRes = [];

            // Get query cursor.
            var cursor = ignite.cache(cacheName).query(qry);

            function onQuery(cursor) {
                var page = cursor.page();

                console.log(">>> Get result on page: " + JSON.stringify(page));

                //Concat query page results.
                fullRes = fullRes.concat(page);

                // IsFinished return true if it is the last page.
                if (cursor.isFinished()) {
                    console.log(">>> People with salaries between 0 and 2000 (queried with SQL query): ");

                    for (var person of fullRes)
                        console.log(person["key"] + " " + person["value"]["name"]);

                    console.log(">>> End of sql query example.");

                    return;
                }

                //Get Promise for next page.
                var nextPromise = cursor.nextPage();

                return nextPromise.then(onQuery);
            }

            // Get query's page.
            return cursor.nextPage().then(onQuery);
        })
    }

    // Initialize cache for people.
    function initializeEntries() {
        var key1 = 1;
        var value1 = {"name" : "John", "id" : 1, "salary" : 2000, "address" : {"street" : "1st Avenue"}};
        var key2 = 2;
        var value2 = {"name" : "Jane", "id" : 2, "salary" : 1000, "address" : {"street" : "1st Avenue"}};
        var key3 = 3;
        var value3 = {"name" : "John", "id" : 3, "salary" : 1000, "address" : {"street" : "1st Avenue"}};
        var key4 = 4;
        var value4 = {"name" : "Jane", "id" : 4, "salary" : 2000, "address" : {"street" : "1st Avenue"}};
        var key5 = 5;
        var value5 = {"name" : "Ann", "id" : 5, "salary" : 3000, "address" : {"street" : "1st Avenue"}};

        return [new CacheEntry(key1, value1), new CacheEntry(key2, value2),
            new CacheEntry(key3, value3), new CacheEntry(key4, value4)];
    }
}

main();