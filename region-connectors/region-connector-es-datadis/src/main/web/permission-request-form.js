// SPDX-FileCopyrightText: 2023-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

import { html, nothing } from "lit";
import { ifDefined } from "lit/directives/if-defined.js";
import { unsafeSVG } from "lit/directives/unsafe-svg.js";
import PermissionRequestFormBase from "../../../../shared/src/main/web/permission-request-form-base.js";
import logo from "../resources/datadis-logo.svg?raw";

import "https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.15.0/cdn/components/input/input.js";
import "https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.15.0/cdn/components/button/button.js";
import "https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.15.0/cdn/components/alert/alert.js";
import "https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.15.0/cdn/components/spinner/spinner.js";

class PermissionRequestForm extends PermissionRequestFormBase {
  static properties = {
    connectionId: { attribute: "connection-id" },
    dataNeedId: { attribute: "data-need-id" },
    accountingPointId: { attribute: "accounting-point-id" },
    customerIdentification: { attribute: "customer-identification" },
    jumpOffUrl: { attribute: "jump-off-url" },
    _isSentToPermissionAdministrator: { type: Boolean },
    _isSubmitDisabled: { type: Boolean },
    _isVerifying: { type: Boolean },
    _requestCreated: { type: Boolean },
    _errorMessage: { state: true },
  };

  permissionId = null;

  constructor() {
    super();
  }

  connectedCallback() {
    super.connectedCallback();

    this.addEventListener(
      "eddie-request-sent-to-permission-administrator",
      () => {
        this._isSentToPermissionAdministrator = true;
      }
    );
  }

  handleSubmit(event) {
    event.preventDefault();
    if (this._isSubmitDisabled || this._requestCreated) return;
    this._errorMessage = "";

    const formData = new FormData(event.target);
    let payload = {
      connectionId: this.connectionId,
      meteringPointId: this.accountingPointId || formData.get("meteringPointId"),
      nif: this.customerIdentification || formData.get("nif"),
      dataNeedIds: this.dataNeedId.split(","),
    };

    this._isSubmitDisabled = true;

    return this.createPermissionRequest(payload)
      .then(({ permissionIds }) => {
        if (!Array.isArray(permissionIds) || !permissionIds.length) {
          throw new Error("The server did not return a permission request. Please try again.");
        }
        this.permissionId = permissionIds.join(",");
        // A missed or delayed status-stream event must not leave the submitted form stuck.
        // Creation is not consent: EDDIE still verifies the response with Datadis.
        this._requestCreated = true;
      })
      .catch((error) => {
        this._isSubmitDisabled = false;
        this.showError(error);
      });
  }

  showError(error) {
    this._errorMessage = error instanceof Error ? error.message : String(error);
    this.error(this._errorMessage);
  }

  accepted() { return this.submitDecision("accepted"); }

  rejected() { return this.submitDecision("rejected"); }

  async submitDecision(decision) {
    if (!this.permissionId || this._isVerifying) return;
    this._errorMessage = "";
    this._isVerifying = true;
    try {
      const response = await fetch(`${this.requestUrl}/${decision}?permission-id=${encodeURIComponent(this.permissionId)}`, {
        method: "PATCH",
        headers: { Authorization: "Bearer " + this.bearerToken },
      });
      if (!response.ok) {
        throw new Error(`Your response could not be saved (${response.status}). Please try again.`);
      }
    } catch (error) {
      this._isVerifying = false;
      this.showError(error);
    }
  }

  render() {
    return html`
      ${this._errorMessage ? html`<sl-alert open variant="danger" role="alert">${this._errorMessage}</sl-alert>` : nothing}
      <form
        id="request-form"
        ?hidden="${this._requestCreated || this._isSentToPermissionAdministrator}"
      >
        <sl-input
          label="CUPS"
          id="meteringPointId"
          type="text"
          name="meteringPointId"
          .helpText=${this.accountingPointId
            ? "The service has already provided a CUPS value. If this value is incorrect, please contact the service provider."
            : nothing}
          .value="${ifDefined(this.accountingPointId)}"
          .disabled="${!!this.accountingPointId}"
          required
        ></sl-input>

        <br />

        <sl-input
          label="DNI/NIF"
          type="text"
          id="nif"
          name="nif"
          placeholder="25744101M"
          .help-text=${this.customerIdentification
            ? "We require the identification number you use to log into the Datadis web portal to request permission."
            : nothing}
          .value="${ifDefined(this.customerIdentification)}"
          .disabled="${!!this.customerIdentification}"
          required
          pattern="[a-zA-Z0-9]{9}"
        ></sl-input>

        <br />

        <sl-button
          ?disabled="${this._isSubmitDisabled}"
          type="submit"
          variant="primary"
        >
          ${this._isSubmitDisabled ? "Connecting…" : "Connect"}
        </sl-button>
      </form>

      <div ?hidden="${!this._requestCreated && !this._isSentToPermissionAdministrator}">
        <p>
          ${this._isSentToPermissionAdministrator
            ? "Your request has been sent to Datadis."
            : "Your permission request has been created."}
          Open Datadis and approve the authorization request when it appears.
          If it is not visible yet, allow a few minutes and refresh the Datadis portal.
        </p>
        <a
          href="${this.jumpOffUrl}"
          target="_blank"
          style="display: inline-block; background: #5D208B; border-radius: 2em; padding: 0.5em 1em 0.5em 1em"
        >
          ${unsafeSVG(logo)}
        </a>
        <p>
          Please let us know once you have accepted or rejected the
          authorization request.
        </p>
        <div>
          <sl-button
            variant="success"
            @click="${this.accepted}"
            ?disabled="${this._isVerifying || !this.permissionId}"
          >
            Accepted
          </sl-button>
          <sl-button
            variant="danger"
            @click="${this.rejected}"
            ?disabled="${this._isVerifying || !this.permissionId}"
          >
            Rejected
          </sl-button>
        </div>

        ${this._isVerifying
          ? html`
              <br />
              <sl-alert open>
                <sl-spinner slot="icon"></sl-spinner>

                <p>
                  We are verifying your response with Datadis. This might take a
                  few minutes.
                </p>
              </sl-alert>
            `
          : nothing}
      </div>
    `;
  }
}

export default PermissionRequestForm;
