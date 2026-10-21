Exposes a websocket on :8989/record which can receive audio data from the jitsi videobridge.
Records the audio in multi track mka format
Can trigger a custom script after the audio streams are finished being received

## Recording resource boundaries

The internal recording WebSocket expects trusted Jitsi Videobridge senders.
Do not publish its port to untrusted clients without a separately enforced
network/proxy admission boundary. No new authentication protocol is introduced
by the resource fixes below.

`jitsi-multitrack-recorder.recording` supports these finite limits:

- `max-tracks = 4096`: cumulative physical Matroska tracks over one recording,
  including new tracks caused by long-gap resets under an existing name.
- `max-track-metadata-bytes = 1024`: UTF-8 bytes per track name or endpoint ID.

Checks precede track, writer and child-logger allocation. An over-limit event
is rejected by the existing consumer exception path; this does not close the
WebSocket. Valid existing tracks can continue. Directory claims use atomic
creation and numbered collision suffixes so concurrent same-meeting sessions
cannot share a recording output. Files and symlinks count as collisions; other
filesystem errors abort rather than returning an unowned path.

`RecordingBoundaryTest` retains cumulative/reset, metadata, rejected-wrapper
allocation, concurrent directory and filesystem-error regressions. Run
`mvn verify` for the full test and Kotlin-format gates, including the existing
1000-track fixture, queue-backpressure and shutdown-race tests.
