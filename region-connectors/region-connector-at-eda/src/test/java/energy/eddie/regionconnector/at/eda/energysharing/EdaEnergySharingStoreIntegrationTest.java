// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.regionconnector.at.eda.persistence.EnergySharingRecord;
import energy.eddie.regionconnector.at.eda.persistence.JpaEnergySharingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(EdaEnergySharingStore.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class EdaEnergySharingStoreIntegrationTest {
    @SuppressWarnings("unused")
    @Container @ServiceConnection
    private static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:15-alpine");
    @Autowired private EdaEnergySharingStore store;
    @Autowired private JpaEnergySharingRepository repository;

    @BeforeEach void clean(){repository.deleteAll();}
    private EnergySharingRecord row(String id){return new EnergySharingRecord(id,"scope","OPERATION","immutable-request","market"+id,"0");}

    @Test void insertCommitsBeforeTransportAndDuplicateCannotOverwriteIdentity() {
        store.insert(row("r1"));
        assertEquals("immutable-request",repository.findById("r1").orElseThrow().fingerprint());
        var duplicate = new EnergySharingRecord("r1","other","OPERATION","different","othermarket","overwritten");
        assertThrows(RuntimeException.class,()->store.insert(duplicate));
        assertEquals("0",store.find("r1").orElseThrow().payload());
        assertEquals("scope",store.find("r1").orElseThrow().scope());
    }
    @Test void failedUpdateRollsBackWithoutLosingLastConfirmedState() {
        store.insert(row("r2"));
        assertThrows(IllegalStateException.class,()->store.update("r2",old->{throw new IllegalStateException("parser failed");}));
        assertEquals("0",store.find("r2").orElseThrow().payload());
        store.update("r2",old->"confirmed");
        assertEquals("confirmed",repository.findByMarketId("marketr2").orElseThrow().payload());
    }
    @Test void concurrentUpdatesSerializeAndDoNotLoseInboundEvents() throws Exception {
        store.insert(row("r3"));
        try(var executor=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);
            Callable<Void> task=()->{gate.await();for(int i=0;i<15;i++)store.update("r3",old->Integer.toString(Integer.parseInt(old)+1));return null;};
            var first=executor.submit(task);var second=executor.submit(task);gate.countDown();
            first.get(30,TimeUnit.SECONDS);second.get(30,TimeUnit.SECONDS);
        }
        assertEquals("30",store.find("r3").orElseThrow().payload());
        assertEquals(30,store.find("r3").orElseThrow().version());
    }
    @Test void competingDailyReservationsHaveOnlyOneWinner() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);
            Callable<Boolean> task=()->{gate.await();try{store.insert(row("daily"));return true;}catch(RuntimeException e){return false;}};
            var first=executor.submit(task);var second=executor.submit(task);gate.countDown();
            assertNotEquals(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
        }
        assertEquals(1,repository.count());
    }

    @Test void durableReadsKeepMarketCorrelationSeparateFromScopeAndRecordKind() {
        store.insert(row("operation"));
        store.insert(new EnergySharingRecord("foreign","another-scope","OPERATION","fingerprint","foreign-market","{}"));
        store.insert(new EnergySharingRecord("snapshot","scope","SNAPSHOT","fingerprint","snapshot-market","{}"));
        assertEquals("operation",store.byMarketId("marketoperation").orElseThrow().id());
        assertTrue(store.byMarketId("missing").isEmpty());
        assertEquals(java.util.List.of("operation"),store.records("scope","OPERATION").stream().map(EnergySharingRecord::id).toList());
        assertTrue(store.records("missing","OPERATION").isEmpty());
        assertThrows(java.util.NoSuchElementException.class,()->store.update("missing",old->"must not insert"));
        assertEquals(3,repository.count());
    }
}
