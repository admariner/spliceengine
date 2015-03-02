package com.splicemachine.si;

import com.google.common.collect.Lists;
import com.splicemachine.si.api.Transactor;
import com.splicemachine.si.api.Txn;
import com.splicemachine.si.api.TxnLifecycleManager;
import com.splicemachine.si.impl.ForwardingLifecycleManager;
import com.splicemachine.si.impl.WriteConflict;
import org.apache.hadoop.hbase.client.RetriesExhaustedWithDetailsException;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.*;

import java.io.IOException;
import java.util.List;

/**
 * Tests that indicate the expected behavior of transactions, particularly
 * with respect to child transactions.
 *
 * This is mainly in place due to frustrations in attempting to conceptually deal
 * with the tests present in SITransactorTest, and thus some behavioral testing
 * may be duplicated here (but hopefully in a clearer and more precise manner).
 *
 * Some of the tests here do NOT reflect expected SQL reality (for example, you cannot
 * create a delete operation until a prior insert operation has either committed or rolled back);
 * however, they serve to illustrate the transactional semantics at the code level (like a tutorial
 * on how to use the transaction system).
 *
 * @author Scott Fines
 * Date: 8/21/14
 */
public class TransactionInteractionTest {
    public static final byte[] DESTINATION_TABLE = Bytes.toBytes("1184");
    boolean useSimple = true;
    static StoreSetup storeSetup;
    static TestTransactionSetup transactorSetup;
    Transactor transactor;
    TxnLifecycleManager control;
    TransactorTestUtility testUtility;
    final List<Txn> createdParentTxns = Lists.newArrayList();

    @SuppressWarnings("unchecked")
    void baseSetUp() {
        transactor = transactorSetup.transactor;
        control = new ForwardingLifecycleManager(transactorSetup.txnLifecycleManager){
            @Override
            protected void afterStart(Txn txn) {
                createdParentTxns.add(txn);
            }
        };
        testUtility = new TransactorTestUtility(useSimple,storeSetup,transactorSetup,transactor,control);
    }

    @BeforeClass
    public static void setupClass(){
        storeSetup = new LStoreSetup();
        transactorSetup = new TestTransactionSetup(storeSetup, true);
    }
    @Before
    public void setUp() throws IOException {
        baseSetUp();
    }

    @After
    public void tearDown() throws Exception {
        for(Txn id:createdParentTxns){
            id.rollback();
        }
    }

    @Test
    public void testInsertThenDeleteWithinSameParentTransactionIsCorrect() throws Exception {
        /*
          * This tests the scenario as follows:
          *
          * 1. start user transaction
          * 2. insert data
          * 3. delete data
          * 4. Read data (no rows seen)
          *
          * With an attempt to duplicate the transactionally correct behavior of
          * the sql engine--that is, complete with proper child transactions etc.
          *
          * In this case, there should be no Write/Write conflict, and at the
          * end of the test no data is visible in the table(e.g. the delete succeeds).
          *
          * Note that, to preserve ACID properties and to obey the principle of least
          * surprise, the normal situation of writes is that writes always occur
          * within a child transaction (which may create its own child transactions
          * as need be); as a result, we use two levels of child transactions
          * to test the behavior
          */

        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);

        //insert the row
        transactionalInsert("scott", userTxn, 29);

        Assert.assertEquals("Incorrect results", "scott age=29 job=null", testUtility.read(userTxn, "scott"));

        transactionalDelete("scott", userTxn);

        Assert.assertEquals("Incorrect results","scott absent",testUtility.read(userTxn,"scott"));
    }

    @Test
    public void testInsertThenRollbackThenInsertNewRowThenScanWillOnlyReturnOneRow() throws Exception {
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);

        Txn child = control.beginChildTransaction(userTxn,DESTINATION_TABLE);

        Txn grandChild = control.beginChildTransaction(child,DESTINATION_TABLE);
        //insert the row
        transactionalInsert("scott10", grandChild, 29);

        Assert.assertEquals("Incorrect results", "scott10 age=29 job=null", testUtility.read(grandChild, "scott10"));

        //rollback
        grandChild.rollback();

        //now insert a second row with a new grandChild

        Txn grandChild2 = control.beginChildTransaction(child,DESTINATION_TABLE);
        transactionalInsert("scott11",grandChild2,29);

        grandChild2.commit();

        child.commit();

        String actual = testUtility.scanAll(userTxn, "scott10", "scott12", null).trim();
        String expected = "scott11 age=29 job=null[ V.age@~9=29 ] ".trim();
        Assert.assertEquals("Incorrect scan results", expected, actual);
    }

    @Test(expected = WriteConflict.class)
    public void testInsertAndDeleteBeforeInsertTransactionCommitsThrowsWriteWriteConflict() throws Throwable {
        /*
         * The scenario is
         *
         * 1. start user transaction
         * 2. insert data in child transaction. Commit grandchild by not insert transaction
         * 3. attempt to delete data.
         * 4. Observe Write/Write conflict
         */

        String name = "scott3";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);

        Txn insertTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn insertChild = control.beginChildTransaction(insertTxn,DESTINATION_TABLE);
        testUtility.insertAge(insertChild,name,29);
        insertChild.commit();

        Txn deleteTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn deleteChild = control.beginChildTransaction(deleteTxn,DESTINATION_TABLE);
        try{
            testUtility.deleteRow(deleteChild,name);
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    @Test(expected = WriteConflict.class)
    public void testInsertAndDeleteBeforeInsertChildTransactionCommitsThrowsWriteWriteConflict() throws Throwable {
        /*
         * The scenario is
         *
         * 1. start user transaction
         * 2. insert data in child transaction. Commit grandchild by not insert transaction
         * 3. attempt to delete data.
         * 4. Observe Write/Write conflict
         */

        String name = "scott3";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);

        Txn insertTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn insertChild = control.beginChildTransaction(insertTxn,DESTINATION_TABLE);
        testUtility.insertAge(insertChild,name,29);

        Txn deleteTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn deleteChild = control.beginChildTransaction(deleteTxn,DESTINATION_TABLE);
        try{
            testUtility.deleteRow(deleteChild,name);
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    @Test(expected = WriteConflict.class)
    public void testInsertAndDeleteInterleavedCommitAndCreationStillThrowsWriteWriteConflict() throws Throwable {
        /*
         * The scenario is
         *
         * 1. start user transaction
         * 2. insert data in child transaction. Commit grandchild but not insert transaction
         * 3. create the delete txn
         * 4. commit the insert txn
         * 5. create the delete child txn
         * 6. attempt to delete the row
         * 4. Observe Write/Write conflict
         */

        String name = "scott4";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);

        Txn insertTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn insertChild = control.beginChildTransaction(insertTxn,DESTINATION_TABLE);
        testUtility.insertAge(insertChild,name,29);
        insertChild.commit();

        Txn deleteTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        insertTxn.commit();
        Txn deleteChild = control.beginChildTransaction(deleteTxn,DESTINATION_TABLE);
        try{
            testUtility.deleteRow(deleteChild, name);
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    /*
     * The following tests use the following write sequence:
     *
     * 1. start user transaction
     * 2. insert data
     * 3. commit user transaction (set up the data)
     * 4. start user transaction
     * 5. delete data
     * 6. insert data
     * 7. read data
     *
     * where the sequence of events of interest are focused within steps 4-7.
     */
    @Test
    public void testDeleteThenInsertWithinSameUserTransactionIsCorrect() throws Exception {
        String name = "scott2";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        transactionalInsert(name, userTxn, 29);
        userTxn.commit();

        userTxn = control.beginTransaction(DESTINATION_TABLE); //get a new transaction

        Assert.assertEquals("Incorrect results",name+" age=29 job=null",testUtility.read(userTxn, name));

        transactionalDelete(name, userTxn);

        transactionalInsert(name, userTxn, 28);

        Assert.assertEquals("Incorrect results",name+" age=28 job=null",testUtility.read(userTxn, name));
    }

    @Test(expected = WriteConflict.class)
    public void testDeleteAndInsertBeforeDeleteTransactionCommitsThrowsWriteWriteConflict() throws Throwable {
        String name = "scott5";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        transactionalInsert(name, userTxn, 29);
        userTxn.commit();
        userTxn = control.beginTransaction(DESTINATION_TABLE);

        Txn deleteTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn deleteChild = control.beginChildTransaction(deleteTxn,DESTINATION_TABLE);
        testUtility.deleteRow(deleteChild, name);
        deleteChild.commit();

        Txn insertTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn insertChild = control.beginChildTransaction(insertTxn,DESTINATION_TABLE);
        try{
            testUtility.insertAge(insertChild, name, 29);
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    @Test(expected = WriteConflict.class)
    public void testDeleteAndInsertInterleavedCommitAndCreatedThrowsWriteWriteConflict() throws Throwable {
        String name = "scott6";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        transactionalInsert(name, userTxn, 29);
        userTxn.commit();
        userTxn = control.beginTransaction(DESTINATION_TABLE);

        Txn deleteTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn deleteChild = control.beginChildTransaction(deleteTxn,DESTINATION_TABLE);
        testUtility.deleteRow(deleteChild, name);
        deleteChild.commit();

        Txn insertTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        deleteTxn.commit();
        Txn insertChild = control.beginChildTransaction(insertTxn,DESTINATION_TABLE);
        try{
            testUtility.insertAge(insertChild,name,29);
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    @Test(expected = WriteConflict.class)
    public void testDeleteAndInsertBeforeDeleteChildTransactionCommitsThrowsWriteWriteConflict() throws Throwable {
        String name = "scott6";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        transactionalInsert(name, userTxn, 29);
        userTxn.commit();
        userTxn = control.beginTransaction(DESTINATION_TABLE);

        Txn deleteTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn deleteChild = control.beginChildTransaction(deleteTxn,DESTINATION_TABLE);
        testUtility.deleteRow(deleteChild, name);

        Txn insertTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn insertChild = control.beginChildTransaction(insertTxn,DESTINATION_TABLE);
        try{
            testUtility.insertAge(insertChild,name,29);
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    /*
     * The following test the "additive" features.
     *
     * Additivity between transactions loosely means that multiple child transactions are
     * expected to (potentially) trample on each other's writes, but that doesn't imply a Write-Write conflict.
     * The most obvious example of this is a bulk insert operation on to a table with a primary key constraint.
     *
     * The canonical example of this is a bulk import operation into a table with a primary key constraint.
     * In that scenario, we have two separate files being imported in parallel, each under their own child transactions,
     * and each file has an entry for a specific row. What we *WANT* to happen is that the system throws
     * a PrimaryKey constraint violation. However, without additivity, what we would GET is a Write/Write conflict.
     *
     * Thus, we want a transactional mode that will not throw the Write/Write conflict, so that instead we can
     * throw the appropriate constraint violation.
     *
     * Typically, you only want to use this feature with Inserts and Deletes--Updates could result in highly
     * non-deterministic results.
     *
     * Now, to the mathematical structure of Additive transactions:
     *
     * Two transactions T1 and T2 are considered additive if and only if the following criteria hold:
     *
     * 1. T1 has been marked as additive
     * 2. T2 has been marked as additive
     * 3. T1.parent = T2.parent (e.g. they are direct relatives)
     *
     *
     * Write characteristics of an Additive Transaction:
     *
     * For writes, we have the following cases:
     *
     * 1. T1 additive:
     *  A. T2 additive:
     *      1. T1.parent == T2.parent => NO WWConflict  (only time this is true)
     *      2. T1.parent != T2.parent => WWConflict
     *  B. T2 not additive => WWConflict
     * 2. T2 additive, T1 not additive => WWConflict
     *
     * Read characteristics of an additive transaction:
     *
     * In general, we would *like* to use normal visibility semantics with additive transactions.
     * However, that is not a possibility, because of the following case:
     *
     * Suppose you are attempting the following sql:
     *
     * insert into t select * from t;
     *
     * With this query, we will construct 1 transaction per region; this means that it will be possible
     * for the following sequence to occur:
     *
     * 1. Child 1 writes row R
     * 2. Child 1 commits
     * 3. Child 2 begins
     * 4. Child 2 reads row R
     *
     * Because Child 1 and Child 2 are at the same level, the normal process would treat them as if they
     * were two individual operations. In this case, since Child 1 has committed before Child 2 began, Snapshot
     * Isolation semantics would allow that child to see writes. In practice, this leads to inconsistent iteration.
     *
     * We want to make it so that Child2 does not see the writes from Child1, even though they are independent
     * from one another. We realize that insertions are "additive", so we discover the second major feature
     * of additive child transactions: writes from one additive child transaction are NOT VISIBLE to
     * any other additive child transaction (as long as they are additive with respect to one another).
     *
     */
    @Test
    public void testTwoAdditiveTransactionsDoNotConflict() throws Exception {
        String name = "scott7";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        Txn child1 = control.beginChildTransaction(userTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,DESTINATION_TABLE);
        Txn child2 = control.beginChildTransaction(userTxn,Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,DESTINATION_TABLE);

        testUtility.insertAge(child1,name,29);
        testUtility.insertJob(child2,name,"plumber");
        child1.commit();
        child2.commit();

        //parent txn can now operate
        Assert.assertEquals("Incorrectly written data!",name+" age=29 job=plumber",testUtility.read(userTxn,name));
    }

    @Test
    public void testTwoAdditiveTransactionsCannotSeeEachOthersWritesEvenAfterCommit() throws Exception {
        /*
         * The purpose of this test is to ensure consistent iteration between two additive transactions.
         *
         * Imagine the following scenario:
         *
         * You have two regions, R1 and R2 with a primary key on column a. You issue "update foo set a = newA". In
         * this case, the update deletes the row at location a and inserts a new record at location newA. So imagine
         * that a is in R1, and newA is in R2; further imagine that the scanner for R2 is behind that of R1. If
         * the additive transaction managing the scan on R2 could see the writes of R1, then R2 would see
         * the entry for newA, and update it to something else, which would result in incorrect results.
         *
         * Therefore, we need to ensure that two additive transactions are NEVER able to see one another, to
         * ensure that we have consistent iteration. This has negative consequences (like detecting write conflicts
         * during writes and so forth), but is necessary.
         */
        String name = "scott10";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        Txn child1 = control.beginChildTransaction(userTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,DESTINATION_TABLE);
        Txn child2 = control.beginChildTransaction(userTxn,Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,null);

        testUtility.insertAge(child1,name,29);
        child1.commit();
        Assert.assertEquals("Additive transaction cannot see sibling's writes",name+" absent",testUtility.read(child2,name));
    }

    @Test(expected=WriteConflict.class)
    public void testOnlyOneAdditiveTransactionConflicts() throws Throwable {
        String name = "scott9";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        Txn child1 = control.beginChildTransaction(userTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,DESTINATION_TABLE);
        Txn child2 = control.beginChildTransaction(userTxn,Txn.IsolationLevel.SNAPSHOT_ISOLATION,false,DESTINATION_TABLE);

        testUtility.insertAge(child1,name,29);
        try{
            testUtility.insertJob(child2,name,"plumber");
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    @Test(expected=WriteConflict.class)
    public void testTwoAdditiveTransactionsWithDifferentParentsConflicts() throws Throwable {
        String name = "scott12";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        Txn child1 = control.beginChildTransaction(userTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,DESTINATION_TABLE);
        Txn u2 = control.beginTransaction(DESTINATION_TABLE);
        Txn child2 = control.beginChildTransaction(u2,Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,DESTINATION_TABLE);

        testUtility.insertAge(child1,name,29);
        try{
            testUtility.insertJob(child2,name,"plumber");
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    @Test(expected=WriteConflict.class)
    public void testAdditiveGrandchildConflictsWithAdditiveChild() throws Throwable {
        String name = "scott8";
        Txn userTxn = control.beginTransaction(DESTINATION_TABLE);
        Txn child1 = control.beginChildTransaction(userTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,DESTINATION_TABLE);
        Txn child2 = control.beginChildTransaction(userTxn,Txn.IsolationLevel.SNAPSHOT_ISOLATION,false,DESTINATION_TABLE);
        Txn grandChild = control.beginChildTransaction(child2,Txn.IsolationLevel.SNAPSHOT_ISOLATION,true,DESTINATION_TABLE);

        testUtility.insertAge(child1,name,29);
        try{
            testUtility.insertJob(grandChild,name,"plumber");
        }catch(RetriesExhaustedWithDetailsException re){
            throw re.getCauses().get(0);
        }
    }

    /**
     * Transactional structure to test:
     * 
     * User Txn
     *   CALL Txn
     *     INSERT Txn
     *     SELECT Txn
     *
     * @throws Exception
     */
    @Test
    public void testInsertThenScanWithinSameParentTransactionIsCorrect() throws Exception {
    	Txn userTxn = control.beginTransaction(DESTINATION_TABLE);

    	Txn callTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE); //create the CallStatement Txn
    	Txn insertTxn = control.beginChildTransaction(callTxn,DESTINATION_TABLE); //create the insert txn
    	testUtility.insertAge(insertTxn,"scott",29); //insert the row
    	insertTxn.commit();

    	Txn selectTxn = control.beginChildTransaction(callTxn,null); //create the select savepoint
    	Assert.assertEquals("Incorrect results", "scott age=29 job=null",testUtility.read(selectTxn, "scott")); //validate it's visible

    	callTxn.rollback();
    }

    /**
     * Transactional structure to test:
     * 
     * User Txn
     *   CALL, SELECT Txn
     *     INSERT Txn
     *
     * @throws Exception
     */
    @Test
    public void testInsertWithChildTransactionThenScanWithParentTransactionIsCorrect() throws Exception {
    	Txn userTxn = control.beginTransaction(DESTINATION_TABLE);

    	Txn callTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE); //create the CallStatement Txn
    	Txn insertTxn = control.beginChildTransaction(callTxn,DESTINATION_TABLE); //create the insert txn
    	testUtility.insertAge(insertTxn,"scott",29); //insert the row
    	insertTxn.commit();

    	Assert.assertEquals("Incorrect results", "scott age=29 job=null",testUtility.read(callTxn, "scott")); //validate it's visible to the parent

    	callTxn.rollback();
    }

    /**
     * This is testing what has been observed happening within stored procedures (callable statements) in Splice/Derby
     * that INSERT a row and then SELECT the row.  The SELECT statement was being wrapped with a SAVEPOINT that was
     * inherited from the CALL statement since the CALL statement was attached to the UserTransaction by Splice, and Derby
     * attempted to wrap the CALL statement with a SAVEPOINT also.  This caused an extra transaction (savepoint) to be
     * created which was then associated with the SELECT statement.  The savepoint around the SELECT was released which
     * was causing the ResultSet to fail scanning the new row since the state of the transaction (savepoint) was COMMITTED.
     * Confusing, eh?
     *
     * Transactional structure to test:
     * 
     * ROOT Txn
     *   CALL Txn (UserTransaction)
     *     SELECT Txn (SAVEPT0)
     *       INSERT Txn (SAVEPT1)
     *
     * @throws Exception
     */
    @Test
    public void testInsertWithGrandchildTransactionThenScanWithParentTransactionIsCorrect() throws Exception {
    	Txn rootTxn = control.beginTransaction(DESTINATION_TABLE);

    	Txn callTxn = control.beginChildTransaction(rootTxn,DESTINATION_TABLE); //create the CallStatement Txn
    	Txn selectTxn = control.beginChildTransaction(callTxn,DESTINATION_TABLE); //create the select savepoint
    	Txn insertTxn = control.beginChildTransaction(selectTxn,DESTINATION_TABLE); //create the insert txn
    	testUtility.insertAge(insertTxn,"scott",29); //insert the row
    	insertTxn.commit();
    	selectTxn.commit();

    	Assert.assertEquals("Incorrect results", "scott age=29 job=null",testUtility.read(selectTxn, "scott")); //validate it's visible

    	callTxn.rollback();
    }

    /************************************************************************************************************/
    /*private helper methods*/
    private void transactionalDelete(String name, Txn userTxn) throws IOException {
        Txn deleteTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn deleteChild = control.beginChildTransaction(deleteTxn,DESTINATION_TABLE);
        testUtility.deleteRow(deleteChild, name);
        deleteChild.commit();
        deleteTxn.commit();
    }

    private void transactionalInsert(String name, Txn userTxn, int age) throws IOException {
        //insert the row
        Txn insertTxn = control.beginChildTransaction(userTxn,DESTINATION_TABLE);
        Txn insertChild = control.beginChildTransaction(insertTxn,DESTINATION_TABLE);
        testUtility.insertAge(insertChild, name, age);
        insertChild.commit(); //make the data visible to the insert parent
        insertTxn.commit();
    }
}
