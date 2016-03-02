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

package org.apache.ignite.internal.processors.cache;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.QueryEntity;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.cache.query.SqlQuery;
import org.apache.ignite.cache.query.annotations.QuerySqlField;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.util.typedef.T4;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;

/**
 *
 */
@SuppressWarnings("unchecked")
public class IgniteCrossCachesDistributedJoinQueryTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** */
    private static final int NODES = 5;

    /** */
    private boolean client;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setIpFinder(IP_FINDER);

        cfg.setClientMode(client);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();

        startGridsMultiThreaded(NODES - 1);

        client = true;

        startGrid(NODES - 1);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();

        super.afterTestsStopped();
    }

    /**
     * @throws Exception If failed.
     */
    public void testCrossCacheDistributedJoin() throws Exception {
        Map<T4<Integer, TestCacheType, TestCacheType, TestCacheType>, Throwable> errors = new LinkedHashMap<>();
        List<T4<Integer, TestCacheType, TestCacheType, TestCacheType>> success = new ArrayList<>();

        int cfgIdx = 0;

        final Set<TestCacheType> personCacheTypes = new LinkedHashSet<TestCacheType>() {{
            add(TestCacheType.REPLICATED_1);
            add(TestCacheType.PARTITIONED_b0_1);
            add(TestCacheType.PARTITIONED_b1_1);
        }};

        final Set<TestCacheType> accCacheTypes = new LinkedHashSet<TestCacheType>() {{
            addAll(personCacheTypes);
            add(TestCacheType.REPLICATED_2);
            add(TestCacheType.PARTITIONED_b0_2);
            add(TestCacheType.PARTITIONED_b1_2);
        }};

        Set<TestCacheType> orgCacheTypes = new LinkedHashSet<TestCacheType>() {{
            addAll(accCacheTypes);
            add(TestCacheType.REPLICATED_3);
            add(TestCacheType.PARTITIONED_b0_3);
            add(TestCacheType.PARTITIONED_b1_3);
        }};

        for (TestCacheType personCacheType : personCacheTypes) {
            for (TestCacheType accCacheType : accCacheTypes) {
                for (TestCacheType orgCacheType : orgCacheTypes) {
                    try {
                        checkDistributedCrossCacheJoin(personCacheType, accCacheType, orgCacheType);

                        success.add(new T4<>(cfgIdx, personCacheType, accCacheType, orgCacheType));
                    }
                    catch (Throwable e) {
                        error("Failed to make distributed cross cache select.", e);

                        errors.put(new T4<>(cfgIdx, personCacheType, accCacheType, orgCacheType), e);
                    }

                    cfgIdx++;
                }
            }
        }

        if (!errors.isEmpty()) {
            int total = personCacheTypes.size() * accCacheTypes.size() * orgCacheTypes.size();

            SB sb = new SB("Test failed for the following " + errors.size() + " combination(s) ("+total+" total):\n");

            for (Map.Entry<T4<Integer, TestCacheType, TestCacheType, TestCacheType>, Throwable> e : errors.entrySet()) {
                T4<Integer, TestCacheType, TestCacheType, TestCacheType> t = e.getKey();

                sb.a("[cfgIdx=" + t.get1() + ", personCache=" + t.get2() + ", accCache=" + t.get3()
                    + ", orgCache=" + t.get4() + ", exception=" + e.getValue() + "]").a("\n");
            }

            sb.a("Successfully finished combinations:\n");

            for (T4<Integer, TestCacheType, TestCacheType, TestCacheType> t : success) {
                sb.a("[cfgIdx=" + t.get1() + ", personCache=" + t.get2() + ", accCache=" + t.get3()
                    + ", orgCache=" + t.get4() + "]").a("\n");
            }

            fail(sb.toString());
        }
    }

    /**
     * @param personCacheType Person cache type.
     * @param accCacheType Account cache type.
     * @param orgCacheType Organization cache type.
     * @throws Exception If failed.
     */
    private void checkDistributedCrossCacheJoin(final TestCacheType personCacheType,
        final TestCacheType accCacheType,
        final TestCacheType orgCacheType) throws Exception {
        info("Checking distributed cross cache join [personCache=" + personCacheType +
            ", accCache=" + accCacheType +
            ", orgCache=" + orgCacheType + "]");

        Collection<TestCacheType> cacheTypes = new ArrayList<TestCacheType>() {{
            add(personCacheType);
            add(accCacheType);
            add(orgCacheType);
        }};

        for (TestCacheType type : cacheTypes) {
            CacheConfiguration cc = cacheConfiguration(type.cacheName,
                type.cacheMode,
                type.backups,
                type == accCacheType,
                type == personCacheType,
                type == orgCacheType
            );

            ignite(0).getOrCreateCache(cc);

            info("Created cache [name=" + type.cacheName + ", mode=" + type.cacheMode + "]");
        }

        awaitPartitionMapExchange();

        try {
            Data data = prepareData();

            IgniteCache accCache = ignite(0).cache(accCacheType.cacheName);

            for (Account account : data.accounts)
                accCache.put(account.id, account);

            IgniteCache personCache = ignite(0).cache(personCacheType.cacheName);

            for (Person person : data.persons)
                personCache.put(person.id, person);

            IgniteCache orgCache = ignite(0).cache(orgCacheType.cacheName);

            for (Organization org : data.orgs)
                orgCache.put(org.id, org);

            List<String> cacheNames = new ArrayList<>();

            cacheNames.add(personCacheType.cacheName);
            cacheNames.add(orgCacheType.cacheName);
            cacheNames.add(accCacheType.cacheName);

            for (int i = 0; i < NODES; i++) {
                log.info("Test node: " + i);

                for (String cacheName : cacheNames) {
                    IgniteCache cache = ignite(i).cache(cacheName);

                    log.info("Use cache: " + cache.getName());

                    checkPersonAccountsJoin(cache,
                        data.accountsPerPerson,
                        accCache.getName(),
                        personCache.getName());

                    checkOrganizationPersonsJoin(cache,
                        data.personsPerOrg,
                        orgCacheType.cacheName,
                        personCacheType.cacheName);

                    checkOrganizationPersonAccountJoin(cache,
                        data.accountsPerOrg,
                        orgCacheType.cacheName,
                        personCacheType.cacheName,
                        accCacheType.cacheName);
                }
            }
        }
        finally {
            ignite(0).destroyCache(accCacheType.cacheName);
            ignite(0).destroyCache(personCacheType.cacheName);
            ignite(0).destroyCache(orgCacheType.cacheName);
        }
    }

    /**
     * Organization ids: [0, 9].
     * Person ids: randoms at [10, 9999]
     * Accounts ids: randoms at [10000, 999_999]
     *
     * @return Data.
     */
    private static Data prepareData() {
        Map<Integer, Integer> personsCntAtOrg = new HashMap<>();
        Map<Integer, Integer> accountsCntForPerson = new HashMap<>();
        Map<Integer, Integer> accountsPerOrg = new HashMap<>();

        Collection<Organization> orgs = new ArrayList<>();
        Collection<Person> persons = new ArrayList<>();
        Collection<Account> accounts = new ArrayList<>();

        final int ORG_CNT = 10;

        for (int id = 0; id < ORG_CNT; id++)
            orgs.add(new Organization(id, "org-" + id));

        Set<Integer> personIds = new HashSet<>();
        Set<Integer> accountIds = new HashSet<>();

        for (int orgId = 0; orgId < ORG_CNT; orgId++) {
            int personsCnt = ThreadLocalRandom.current().nextInt(20);

            int accsPerOrg = 0;

            for (int p = 0; p < personsCnt; p++) {
                int personId = ThreadLocalRandom.current().nextInt(10, 10_000);

                while (!personIds.add(personId))
                    personId = ThreadLocalRandom.current().nextInt(10, 10_000);

                String name = "person-" + personId;

                persons.add(new Person(personId, orgId, name));

                int accountsCnt = ThreadLocalRandom.current().nextInt(10);

                for (int a = 0; a < accountsCnt; a++) {
                    int accountId = ThreadLocalRandom.current().nextInt(10_000, 1000_00);

                    while (!accountIds.add(accountId))
                        accountId = ThreadLocalRandom.current().nextInt(10_000, 1000_000);

                    accounts.add(new Account(accountId, personId));
                }

                accountsCntForPerson.put(personId, accountsCnt);

                accsPerOrg += accountsCnt;
            }

            personsCntAtOrg.put(orgId, personsCnt);
            accountsPerOrg.put(orgId, accsPerOrg);
        }

        return new Data(orgs, persons, accounts, personsCntAtOrg, accountsCntForPerson, accountsPerOrg);
    }

    /**
     * @param cacheName Cache name.
     * @param cacheMode Cache mode.
     * @param backups Number of backups.
     * @param accountIdx Account index flag.
     * @param personIdx Person index flag.
     * @param orgIdx Organization index flag.
     * @return Cache configuration.
     */
    private CacheConfiguration cacheConfiguration(String cacheName,
        CacheMode cacheMode,
        int backups,
        boolean accountIdx,
        boolean personIdx,
        boolean orgIdx) {
        CacheConfiguration ccfg = new CacheConfiguration();

        ccfg.setName(cacheName);

        ccfg.setCacheMode(cacheMode);

        if (cacheMode == PARTITIONED)
            ccfg.setBackups(backups);

        ccfg.setWriteSynchronizationMode(FULL_SYNC);

        List<QueryEntity> entities = new ArrayList<>();

        if (accountIdx) {
            QueryEntity account = new QueryEntity();
            account.setKeyType(Integer.class.getName());
            account.setValueType(Account.class.getName());
            account.addQueryField("personId", Integer.class.getName(), null);

            entities.add(account);
        }

        if (personIdx) {
            QueryEntity person = new QueryEntity();
            person.setKeyType(Integer.class.getName());
            person.setValueType(Person.class.getName());
            person.addQueryField("orgId", Integer.class.getName(), null);
            person.addQueryField("id", Integer.class.getName(), null);
            person.addQueryField("name", String.class.getName(), null);

            entities.add(person);
        }

        if (orgIdx) {
            QueryEntity org = new QueryEntity();
            org.setKeyType(Integer.class.getName());
            org.setValueType(Organization.class.getName());
            org.addQueryField("id", Integer.class.getName(), null);
            org.addQueryField("name", String.class.getName(), null);

            entities.add(org);
        }

        ccfg.setQueryEntities(entities);

        return ccfg;
    }

    /**
     * @param cache Cache.
     * @param cnts Organizations per person counts.
     * @param orgCacheName Organization cache name.
     * @param personCacheName Person cache name.
     */
    private void checkOrganizationPersonsJoin(IgniteCache cache,
        Map<Integer, Integer> cnts,
        String orgCacheName,
        String personCacheName) {
        SqlFieldsQuery qry = new SqlFieldsQuery("select o.name, p.name " +
            "from \"" + orgCacheName + "\".Organization o, \"" + personCacheName + "\".Person p " +
            "where p.orgId = o._key and o._key=?");

        qry.setDistributedJoins(true);

        SqlQuery qry2 = null;

        if (personCacheName.equals(cache.getName())) {
            qry2 = new SqlQuery(Person.class,
                "from \"" + orgCacheName + "\".Organization, \"" + personCacheName + "\".Person " +
                    "where Person.orgId = Organization._key and Organization._key=?"
            );

            qry2.setDistributedJoins(true);
        }


        long total = 0;

        for (int i = 0; i < cnts.size(); i++) {
            qry.setArgs(i);

            if (qry2 != null)
                qry2.setArgs(i);

            List<List<Object>> res = cache.query(qry).getAll();

            assertEquals((int)cnts.get(i), res.size());

            if (qry2 != null) {
                List<List<Object>> res2 = cache.query(qry2).getAll();

                assertEquals((int)cnts.get(i), res2.size());
            }

            total += res.size();
        }

        SqlFieldsQuery qry3 = new SqlFieldsQuery("select count(*) " +
            "from \"" + orgCacheName + "\".Organization o, \"" + personCacheName + "\".Person p where p.orgId = o._key");

        qry3.setDistributedJoins(true);

        List<List<Object>> res = cache.query(qry3).getAll();

        assertEquals(1, res.size());
        assertEquals(total, res.get(0).get(0));
    }

    /**
     * @param cache Cache.
     * @param cnts Accounts per person counts.
     * @param accCacheName Account cache name.
     * @param personCacheName Person cache name.
     */
    private void checkPersonAccountsJoin(IgniteCache cache,
        Map<Integer, Integer> cnts,
        String accCacheName,
        String personCacheName) {
        SqlFieldsQuery qry1 = new SqlFieldsQuery("select p.name " +
            "from \"" + personCacheName + "\".Person p, \"" + accCacheName + "\".Account a " +
            "where p._key = a.personId and p._key=?");

        qry1.setDistributedJoins(true);

        SqlFieldsQuery qry2 = new SqlFieldsQuery("select p.name " +
            "from \"" + personCacheName + "\".Person p, \"" + accCacheName + "\".Account a " +
            "where p.id = a.personId and p.id=?");

        qry2.setDistributedJoins(true);

        SqlQuery qry3 = null;
        SqlQuery qry4 = null;

        if (personCacheName.equals(cache.getName())) {
            qry3 = new SqlQuery(Person.class,
                "from \"" + personCacheName + "\".Person , \"" + accCacheName + "\".Account  " +
                "where Person._key = Account.personId and Person._key=?");

            qry3.setDistributedJoins(true);

            qry4 = new SqlQuery(Person.class,
                "from \"" + personCacheName + "\".Person , \"" + accCacheName + "\".Account  " +
                "where Person.id = Account.personId and Person.id=?");

            qry4.setDistributedJoins(true);
        }


        long total = 0;

        for (Map.Entry<Integer, Integer> e : cnts.entrySet()) {
            qry1.setArgs(e.getKey());

            List<List<Object>> res = cache.query(qry1).getAll();

            assertEquals((int)e.getValue(), res.size());

            total += res.size();

            qry2.setArgs(e.getKey());

            res = cache.query(qry2).getAll();

            assertEquals((int)e.getValue(), res.size());

            if (qry3 != null) {
                qry3.setArgs(e.getKey());

                res = cache.query(qry3).getAll();

                assertEquals((int)e.getValue(), res.size());

                qry4.setArgs(e.getKey());

                res = cache.query(qry4).getAll();

                assertEquals((int)e.getValue(), res.size());
            }
        }

        SqlFieldsQuery[] qrys = new SqlFieldsQuery[2];

        qrys[0] = new SqlFieldsQuery("select count(*) " +
            "from \"" + personCacheName + "\".Person p, \"" + accCacheName + "\".Account" + " a " +
            "where p.id = a.personId");

        qrys[1] = new SqlFieldsQuery("select count(*) " +
            "from \"" + personCacheName + "\".Person p, \"" + accCacheName + "\".Account" + " a " +
            "where p._key = a.personId");

        for (SqlFieldsQuery qry : qrys) {
            qry.setDistributedJoins(true);

            List<List<Object>> res = cache.query(qry).getAll();

            assertEquals(1, res.size());
            assertEquals(total, res.get(0).get(0));
        }
    }

    /**
     * @param cache Cache.
     * @param cnts Accounts per organization count
     * @param orgCacheName Organization cache name.
     * @param personCacheName Person cache name.
     * @param accCacheName Account cache name.
     */
    private void checkOrganizationPersonAccountJoin(IgniteCache cache, Map<Integer, Integer> cnts, String orgCacheName,
        String personCacheName, String accCacheName) {
        SqlFieldsQuery qry = new SqlFieldsQuery("select o.name, p.name, a._key " +
            "from " +
            "\"" + orgCacheName + "\".Organization o, " +
            "\"" + personCacheName + "\".Person p, " +
            "\"" + accCacheName + "\".Account a " +
            "where p.orgId = o._key and p._key = a.personId and o.id = ?");

        qry.setDistributedJoins(true);

        SqlQuery qry2 = null;

        if (accCacheName.equals(cache.getName())) {
            qry2 = new SqlQuery(Account.class, "from " +
                "\"" + orgCacheName + "\".Organization , " +
                "\"" + personCacheName + "\".Person , " +
                "\"" + accCacheName + "\".Account  " +
                "where Person.orgId = Organization._key and Person._key = Account.personId and Organization.id = ?");

            qry2.setDistributedJoins(true);
        }

        long total = 0;

        for (int orgId = 0; orgId < cnts.size(); orgId++) {
            qry.setArgs(orgId);

            List<List<Object>> res = cache.query(qry).getAll();

            assertEquals((int)cnts.get(orgId), res.size());

            total += res.size();

            if (qry2 != null) {
                qry2.setArgs(orgId);

                res = cache.query(qry2).getAll();

                assertEquals((int)cnts.get(orgId), res.size());
            }
        }

        SqlFieldsQuery qry3 = new SqlFieldsQuery("select count(*) " +
            "from " +
            "\"" + orgCacheName + "\".Organization o, " +
            "\"" + personCacheName + "\".Person p, " +
            "\"" + accCacheName + "\".Account a " +
            "where p.orgId = o._key and p._key = a.personId");

        qry3.setDistributedJoins(true);

        List<List<Object>> res = cache.query(qry3).getAll();

        assertEquals(1, res.size());
        assertEquals(total, res.get(0).get(0));

    }

    /**
     *
     */
    private enum TestCacheType {
        /** */
        REPLICATED_1(CacheMode.REPLICATED, 0),

        /** */
        REPLICATED_2(CacheMode.REPLICATED, 0),

        /** */
        REPLICATED_3(CacheMode.REPLICATED, 0),

        /** */
        PARTITIONED_b0_1(CacheMode.PARTITIONED, 0),

        /** */
        PARTITIONED_b0_2(CacheMode.PARTITIONED, 0),

        /** */
        PARTITIONED_b0_3(CacheMode.PARTITIONED, 0),

        /** */
        PARTITIONED_b1_1(CacheMode.PARTITIONED, 1),

        /** */
        PARTITIONED_b1_2(CacheMode.PARTITIONED, 1),

        /** */
        PARTITIONED_b1_3(CacheMode.PARTITIONED, 1),
        ;

        /** */
        final String cacheName;

        /** */
        final CacheMode cacheMode;

        /** */
        final int backups;

        /**
         * @param mode Cache mode.
         * @param backups Backups.
         */
        TestCacheType(CacheMode mode, int backups) {
            cacheName = name();
            cacheMode = mode;
            this.backups = backups;
        }
    }

    /**
     *
     */
    private static class Data {
        /** */
        final Collection<Organization> orgs;

        /** */
        final Collection<Person> persons;

        /** */
        final Collection<Account> accounts;

        /** */
        final Map<Integer, Integer> personsPerOrg;

        /** */
        final Map<Integer, Integer> accountsPerPerson;

        /** */
        final Map<Integer, Integer> accountsPerOrg;

        /**
         * @param orgs Organizations.
         * @param persons Persons.
         * @param accounts Accounts.
         * @param personsPerOrg Count of persons per organization.
         * @param accountsPerPerson Count of accounts per person.
         * @param accountsPerOrg Count of accounts per organization.
         */
        Data(Collection<Organization> orgs, Collection<Person> persons, Collection<Account> accounts,
            Map<Integer, Integer> personsPerOrg, Map<Integer, Integer> accountsPerPerson,
            Map<Integer, Integer> accountsPerOrg) {
            this.orgs = orgs;
            this.persons = persons;
            this.accounts = accounts;
            this.personsPerOrg = personsPerOrg;
            this.accountsPerPerson = accountsPerPerson;
            this.accountsPerOrg = accountsPerOrg;
        }
    }

    /**
     *
     */
    private static class Account implements Serializable {
        /** */
        private int id;

        /** */
        @QuerySqlField
        private int personId;

        /**
         * @param id ID.
         * @param personId Person ID.
         */
        Account(int id, int personId) {
            this.id = id;
            this.personId = personId;
        }
    }

    /**
     *
     */
    private static class Person implements Serializable {
        /** */
        int id;

        /** */
        @QuerySqlField
        int orgId;

        /** */
        @QuerySqlField
        String name;

        /**
         * @param id ID.
         * @param orgId Organization ID.
         * @param name Name.
         */
        public Person(int id, int orgId, String name) {
            this.id = id;
            this.orgId = orgId;
            this.name = name;
        }
    }

    /**
     *
     */
    private static class Organization implements Serializable {
        /** */
        @QuerySqlField
        int id;

        /** */
        @QuerySqlField
        String name;

        /**
         * @param id ID.
         * @param name Name.
         */
        Organization(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
