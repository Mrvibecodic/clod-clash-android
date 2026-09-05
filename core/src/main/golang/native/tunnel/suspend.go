package tunnel

func Suspend(s bool) {
	// Called on ACTION_SCREEN_OFF/ACTION_SCREEN_ON; intentionally a no-op.
	//
	// WARNING: do not call the core's tunnel.OnSuspend/OnRunning here: OnSuspend
	// rejects new TCP connections and drops UDP packets while the screen is off.
}
