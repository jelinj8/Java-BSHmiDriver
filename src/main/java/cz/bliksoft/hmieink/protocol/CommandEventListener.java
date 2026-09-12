package cz.bliksoft.hmieink.protocol;

/**
 * Receives frames the device pushed unsolicited - i.e. that don't correlate to
 * any request currently pending on a {@link CommandClient} - such as
 * BUTTON_EVENT (§11) or GPIO_EVENT (§15.4).
 */
public interface CommandEventListener {

	void onEvent(Frame frame);
}
