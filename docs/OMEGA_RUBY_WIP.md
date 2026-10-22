# Omega Ruby work checkpoint

Paused at the user’s request on 2026-10-22. Resume only when requested.

The experimental 3DS player integrates the pinned Azahar 2126.2 Android ARM64 libretro core, imports decrypted game files, and provides stacked screens, touch, Circle Pad, and buttons. The offline Omega Ruby guide has 70 chapters and 280 manual steps. The live companion (party, bag, battle, map, dex, automatic story progress) is unfinished; no live reader is implemented.

Validation: debug APK builds succeeded; five cartridge-header unit tests passed. Android library and guide screens launched in a disposable emulator. The supplied game reached its opening sequence using the official macOS core. Android game rendering is unverified: the test emulator exposes GLES 3.1 while this integration requires GLES 3.2. Final lint completion and the latest guide contrast changes need verification. No release was published for this checkpoint.

Resume: guard concurrent imports/start requests and lifecycle transitions; verify rendering on a suitable tablet; visually check guide contrast and validate walkthrough accuracy. A possible read-only live-party route is the pinned Android core’s exported Core::System::s_instance, System::Memory(), and MemorySystem::GetPointer(unsigned int) symbols, called between frames. This is research only, not implemented. Candidate ORAS party address 0x08CF727C, slot stride 484; encrypted PK6 block 232 bytes, party stats at slot+344 (22 bytes). Verify version-specific addresses and stable reads before relying on them. Libretro memory descriptors were published before the game heap existed, so they did not expose the needed region in the desktop probe.

Local test scripts, logs, downloaded cores/source, and screenshots remain in the workspace outside this commit. ROMs, saves, and keys must remain excluded. Existing melonDS submodule modifications predate this checkpoint and are preserved separately in the working tree.
