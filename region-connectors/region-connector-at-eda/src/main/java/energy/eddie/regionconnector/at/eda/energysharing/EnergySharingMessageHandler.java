// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

@FunctionalInterface
public interface EnergySharingMessageHandler {
    default boolean accepts(String code) {
        return java.util.Set.of("ANTWORT_ECON","ZUSTIMMUNG_ECON","ABSCHLUSS_ECON","ABLEHNUNG_ECON","ABBRUCH_ECON",
                "ANTWORT_CPF","ABLEHNUNG_CPF","ANTWORT_ECC","ABLEHNUNG_ECC","SENDEN_ECP","ABLEHNUNG_ECP",
                "ANTWORT_CCMS","ABLEHNUNG_CCMS","AUFHEBUNG_CCMC","AUFHEBUNG_CCMI").contains(code);
    }
    /** Return true only when this durable workflow owns and successfully processes the message. */
    boolean handle(String messageCode, byte[] xml);
    default void transportStatus(String conversationId,String state) {}
}
