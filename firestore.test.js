const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_streamer_1";
const BOB_UID = "bob_teammate_2";
const CHARLIE_UID = "charlie_outsider_3";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- 1. UNAUTHENTICATED ACCESS DENIAL ---

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = pastDate();

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("voice_rooms").where("visibility", "==", "PUBLIC").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Anon",
      totalStreamDurationSec: 0,
      totalSessions: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

// --- 2. USER PROFILE OWNER ISOLATION & SCHEMA BOUNDS ---

test("UserProfile: owner create/update succeeds; cross-user & ghost fields fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(5000);

  // Valid Kotlin create payload (no @DocumentId `id` key)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "ZroAlice",
      avatarUrl: "https://example.com/alice.png",
      totalStreamDurationSec: 0,
      totalSessions: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Owner partial update succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "ZroAlicePro",
      totalStreamDurationSec: 3600,
      totalSessions: 2,
      updatedAt: pastDate(1000),
    })
  );

  // Cross-user read & write rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hijacked",
      updatedAt: pastDate(500),
    })
  );

  // Shadow/ghost field & future timestamp rejected
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      totalStreamDurationSec: 0,
      totalSessions: 0,
      isAdmin: true, // ghost field
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "BobFuture",
      totalStreamDurationSec: 0,
      totalSessions: 0,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

// --- 3. STREAM SESSIONS: OWNER ISOLATION, TERMINAL STATE LOCK & QUERY ---

test("StreamSessions: create, finalize to ENDED, terminal lock, and cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(10000);

  // Mirror Kotlin createStreamSession payload
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("stream_sessions").doc("sess_1").set({
      userId: ALICE_UID,
      title: "Ranked Push 720p60",
      description: "Live with squad voice",
      visibility: "PUBLIC",
      resolutionLabel: "720p",
      fps: 60,
      bitrateKbps: 6000,
      durationSec: 0,
      droppedFrames: 0,
      peakBitrateKbps: 6000,
      status: "LIVE",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Owner list query succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("stream_sessions").get()
  );

  // Cross-user list & get fail
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("stream_sessions").get()
  );

  // Mirror Kotlin finalizeStreamSession partial update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("stream_sessions").doc("sess_1").update({
      durationSec: 1845,
      droppedFrames: 4,
      peakBitrateKbps: 6200,
      status: "ENDED",
      updatedAt: pastDate(1000),
    })
  );

  // Terminal state lock: once ENDED, further updates must be rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("stream_sessions").doc("sess_1").update({
      durationSec: 9999,
      updatedAt: pastDate(500),
    })
  );

  // Extra `@DocumentId` `id` field rejected on create
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("stream_sessions").doc("sess_2").set({
      id: "sess_2",
      userId: ALICE_UID,
      title: "Bad Id Field",
      visibility: "PUBLIC",
      resolutionLabel: "720p",
      fps: 30,
      bitrateKbps: 4500,
      durationSec: 0,
      droppedFrames: 0,
      peakBitrateKbps: 4500,
      status: "LIVE",
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

// --- 4. VOICE ROOMS & PARENT-GATE PARTICIPANTS ---

test("VoiceRooms: create, join, update participant, leave, query alignment & Parent Gate", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const ts = pastDate(8000);
  const roomCode = "ZRO-7K4M";

  // 1. Alice creates a PRIVATE room + her participant doc in a batch (exact Kotlin flow)
  const createBatch = aliceDb.batch();
  const roomRef = aliceDb.collection("voice_rooms").doc(roomCode);
  const alicePartRef = roomRef.collection("participants").doc(ALICE_UID);
  createBatch.set(roomRef, {
    hostUid: ALICE_UID,
    roomCode: roomCode,
    name: "Clutch Squad",
    visibility: "PRIVATE",
    active: true,
    maxParticipants: 6,
    memberUids: [ALICE_UID],
    createdAt: ts,
    updatedAt: ts,
  });
  createBatch.set(alicePartRef, {
    userId: ALICE_UID,
    displayName: "ZroAlice",
    muted: false,
    speaking: false,
    audioLevel: 0,
    streamConsent: true,
    connectionQuality: "EXCELLENT",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertSucceeds(createBatch.commit());

  // 2. Unfiltered list on voice_rooms must fail; PUBLIC query & memberUids query succeed
  await assertFails(charlieDb.collection("voice_rooms").get());
  await assertSucceeds(
    charlieDb.collection("voice_rooms").where("visibility", "==", "PUBLIC").get()
  );
  await assertSucceeds(
    aliceDb.collection("voice_rooms").where("memberUids", "array-contains", ALICE_UID).get()
  );

  // 3. Charlie (non-member) CANNOT read Alice's participant doc in PRIVATE room (Parent Gate)
  await assertFails(
    charlieDb.collection("voice_rooms").doc(roomCode).collection("participants").doc(ALICE_UID).get()
  );

  // 4. Unfiltered list on participants subcollection is strictly disabled
  await assertFails(
    aliceDb.collection("voice_rooms").doc(roomCode).collection("participants").get()
  );

  // 5. Bob joins the room via Room Code in a batch (exact Kotlin joinVoiceRoom flow)
  const joinBatch = bobDb.batch();
  const bobRoomRef = bobDb.collection("voice_rooms").doc(roomCode);
  const bobPartRef = bobRoomRef.collection("participants").doc(BOB_UID);
  joinBatch.update(bobRoomRef, {
    memberUids: [ALICE_UID, BOB_UID],
    updatedAt: pastDate(3000),
  });
  joinBatch.set(bobPartRef, {
    userId: BOB_UID,
    displayName: "BobTeammate",
    muted: false,
    speaking: true,
    audioLevel: 65,
    streamConsent: true,
    connectionQuality: "GOOD",
    udpEndpoint: "192.168.1.42:41920",
    createdAt: pastDate(3000),
    updatedAt: pastDate(3000),
  });
  await assertSucceeds(joinBatch.commit());

  // 6. Now Bob IS a member, so Bob CAN read Alice's participant doc via Parent Gate
  await assertSucceeds(
    bobDb.collection("voice_rooms").doc(roomCode).collection("participants").doc(ALICE_UID).get()
  );

  // 7. Bob can update his own speaking/mute/consent state, but CANNOT modify Alice's state
  await assertSucceeds(
    bobPartRef.update({
      muted: true,
      speaking: false,
      audioLevel: 0,
      streamConsent: false,
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(
    bobDb.collection("voice_rooms").doc(roomCode).collection("participants").doc(ALICE_UID).update({
      muted: true,
      updatedAt: pastDate(1000),
    })
  );

  // 8. Bob leaves the room (exact Kotlin leaveVoiceRoom batch)
  const leaveBatch = bobDb.batch();
  leaveBatch.delete(bobPartRef);
  leaveBatch.update(bobRoomRef, {
    memberUids: [ALICE_UID],
    updatedAt: pastDate(500),
  });
  await assertSucceeds(leaveBatch.commit());

  // 9. Instant Parent-Gate revocation: Bob can no longer read Alice's participant doc
  await assertFails(
    bobDb.collection("voice_rooms").doc(roomCode).collection("participants").doc(ALICE_UID).get()
  );
});
