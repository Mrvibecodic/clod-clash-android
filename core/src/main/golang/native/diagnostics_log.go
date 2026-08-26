package main

import "github.com/metacubex/mihomo/log"

const (
	diagnosticsEventNativeStartRequested                = 38
	diagnosticsEventNativeStartRejectedControllerSecret = 39
	diagnosticsEventNativeStartRejectedAuth             = 40
	diagnosticsEventNativeStartRejectedEndpoint         = 41
	diagnosticsEventNativeStartRejectedRemotePort       = 42
	diagnosticsEventNativeStartIgnoredAlreadyRunning    = 43
	diagnosticsEventNativeClientCreated                 = 44
	diagnosticsEventNativeClientCreateFailed            = 45
	diagnosticsEventNativeTransportWorkerStarted        = 46
	diagnosticsEventNativeTransportStartFailed          = 47
	diagnosticsEventNativeProbeStarted                  = 48
	diagnosticsEventNativeProbeReady                    = 49
	diagnosticsEventNativeProbeAccessDenied             = 50
	diagnosticsEventNativeProbeUnreachable              = 51
	diagnosticsEventNativeTransportStoppedExpected      = 52
	diagnosticsEventNativeTransportStoppedUnexpected    = 53
	diagnosticsEventNativeStopRequested                 = 54
	diagnosticsEventNativeStopCompleted                 = 55
	diagnosticsEventNativeStopNoop                      = 56
)

var diagnosticsEventPayloads = map[int]string{
	1:  "event=ui_enable_requested result=requested",
	2:  "event=ui_enable_rejected_not_configured result=failed reason=not_configured",
	3:  "event=ui_enable_rejected_endpoint_missing result=failed reason=endpoint_missing",
	4:  "event=ui_enable_rejected_vpn_stopped result=failed reason=vpn_stopped",
	5:  "event=ui_enable_rejected_credential_unavailable result=failed reason=credential_unavailable",
	6:  "event=ui_disable_requested result=requested",
	7:  "event=settings_save_requested result=requested",
	8:  "event=settings_save_rejected_vpn_running result=failed reason=vpn_running",
	9:  "event=settings_save_rejected_endpoint_invalid result=failed reason=endpoint_invalid",
	10: "event=settings_save_rejected_credential_incomplete result=failed reason=credential_incomplete",
	11: "event=settings_save_rejected_credential_missing result=failed reason=credential_missing",
	12: "event=credential_save_succeeded result=success",
	13: "event=credential_save_failed result=failed reason=keystore_write_failed",
	14: "event=endpoint_save_succeeded result=success",
	15: "event=credential_delete_requested result=requested",
	16: "event=credential_delete_rejected_vpn_running result=failed reason=vpn_running",
	17: "event=credential_delete_succeeded result=success",
	18: "event=credential_delete_failed result=failed reason=keystore_clear_failed",
	19: "event=settings_reset_credentials_cleared result=success",
	20: "event=service_module_started result=success",
	21: "event=service_pending_events_flushed result=success",
	22: "event=service_mode_enabled result=received",
	23: "event=service_mode_disabled result=received",
	24: "event=service_session_resolved result=success",
	25: "event=service_session_rejected_endpoint_invalid result=failed reason=endpoint_invalid",
	26: "event=service_session_rejected_credential_missing result=failed reason=credential_missing",
	27: "event=service_session_rejected_credential_invalid result=failed reason=credential_invalid",
	28: "event=service_controller_diagnostics_applied result=success",
	29: "event=service_controller_local_applied result=success",
	30: "event=service_controller_apply_failed result=failed reason=controller_apply_failed",
	31: "event=service_tunnel_start_requested result=requested",
	32: "event=service_cleanup_completed result=success",
	33: "event=state_connecting result=transition",
	34: "event=state_ready result=transition",
	35: "event=state_configuration_error result=transition",
	36: "event=state_access_denied result=transition",
	37: "event=state_unreachable result=transition",
	38: "event=native_start_requested result=requested",
	39: "event=native_start_rejected_controller_secret result=failed reason=controller_secret_missing",
	40: "event=native_start_rejected_auth result=failed reason=tunnel_auth_missing",
	41: "event=native_start_rejected_endpoint result=failed reason=endpoint_invalid",
	42: "event=native_start_rejected_remote_port result=failed reason=remote_port_invalid",
	43: "event=native_start_ignored_already_running result=ignored reason=already_running",
	44: "event=native_client_created result=success",
	45: "event=native_client_create_failed result=failed reason=client_create_failed",
	46: "event=native_transport_worker_started result=success",
	47: "event=native_transport_start_failed result=failed reason=transport_start_failed",
	48: "event=native_probe_started result=success",
	49: "event=native_probe_ready result=success",
	50: "event=native_probe_access_denied result=failed reason=access_denied",
	51: "event=native_probe_unreachable result=failed reason=unreachable",
	52: "event=native_transport_stopped_expected result=success",
	53: "event=native_transport_stopped_unexpected result=failed reason=transport_stopped",
	54: "event=native_stop_requested result=requested",
	55: "event=native_stop_completed result=success",
	56: "event=native_stop_noop result=ignored reason=not_running",
	57: "event=unknown result=ignored reason=unknown_code",
	58: "event=settings_opened result=success",
	59: "event=ui_enable_cancelled result=cancelled",
	60: "event=settings_closed result=success",
	61: "event=endpoint_save_failed result=failed reason=preferences_write_failed",
	62: "event=credential_preserved result=success",
	63: "event=state_stopped result=transition",
	64: "event=service_pending_mode_applied result=success",
	65: "event=ui_mode_command_store_failed result=failed reason=preferences_write_failed",
	66: "event=service_mode_command_ack_failed result=failed reason=preferences_write_failed",
}

func diagnosticsRecordEvent(code int) {
	payload, ok := diagnosticsEventPayloads[code]
	if !ok {
		payload = diagnosticsEventPayloads[57]
	}
	log.Infoln("[APP] [Diagnostics] %s", payload)
}
