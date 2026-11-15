// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "energy_sharing_record", schema = "at_eda")
public class EnergySharingRecord {
    @Id private String id="";
    private String scope="";
    private String kind="";
    private String fingerprint="";
    @Column(name = "market_id") private String marketId="";
    @Column(columnDefinition = "text") private String payload="";
    @Version private long version;
    protected EnergySharingRecord() {}
    public EnergySharingRecord(String id, String scope, String kind, String fingerprint, String marketId, String payload) {
        this.id=id; this.scope=scope; this.kind=kind; this.fingerprint=fingerprint; this.marketId=marketId; this.payload=payload;
    }
    public String id() { return id; }
    public String scope() { return scope; }
    public String kind() { return kind; }
    public String fingerprint() { return fingerprint; }
    public String marketId() { return marketId; }
    public String payload() { return payload; }
    public long version() { return version; }
    public void payload(String value) { payload=value; }
}
