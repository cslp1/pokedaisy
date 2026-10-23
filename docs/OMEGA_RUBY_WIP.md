# Omega Ruby work checkpoint

Resumed at the user’s request on 2026-10-23.

The experimental 3DS player integrates the pinned Azahar 2126.2 Android ARM64 libretro core, imports decrypted game files, and provides stacked screens, touch, Circle Pad, and buttons. The offline Omega Ruby guide has 70 chapters and 280 manual steps. The live companion (party, bag, battle, map, dex, automatic story progress) is unfinished; an opt-in experimental live-slot reader is now implemented, but its address, party count and tablet behavior are not yet verified.

Validation: debug APK builds succeeded; five cartridge-header unit tests passed. Android library and guide screens launched in a disposable emulator. The supplied game reached its opening sequence using the official macOS core. Android game rendering is unverified: the test emulator exposes GLES 3.1 while this integration requires GLES 3.2. Final lint completion and the latest guide contrast changes need verification. No release was published for this checkpoint.

Resume: guard concurrent imports/start requests and lifecycle transitions; verify rendering on a suitable tablet; visually check guide contrast and validate walkthrough accuracy. A possible read-only live-party route is the pinned Android core’s exported Core::System::s_instance, System::Memory(), and MemorySystem::GetPointer(unsigned int) symbols, called between frames. This bridge is now implemented behind the experimental live-party checkbox. Candidate ORAS party address 0x08CF727C, slot stride 484; encrypted PK6 block 232 bytes, party stats at slot+344 (22 bytes). Verify version-specific addresses and stable reads before relying on them. Libretro memory descriptors were published before the game heap existed, so they did not expose the needed region in the desktop probe.

Local test scripts, logs, downloaded cores/source, and screenshots remain in the workspace outside this commit. ROMs, saves, and keys must remain excluded. Existing melonDS submodule modifications predate this checkpoint and are preserved separately in the working tree.

## Resumed implementation

`Gen6Party.java` decrypts the live 232-byte PK6 structure and the separate 22-byte stats segment, verifies the checksum, unshuffles all 32 shuffle values, and rejects invalid species, levels, moves and HP. The JNI bridge reads six 484-byte slots twice between emulated frames, page by page, through the pinned core’s exported const GetPointer API. It never writes guest memory. No arbitrary memory-read JNI API is exposed. The UI enables reads only for product CTR-P-ECRA and only when the user selects the experimental checkbox. Valid slots are explicitly not described as the current party because the count is not mapped and old slots may remain in memory.

Format research sources: PKHeX PK6.cs and PokeCrypto.cs at https://github.com/kwsch/PKHeX/tree/master/PKHeX.Core/PKM and the address/layout observations in https://github.com/EverOddish/PokeStreamer-Tools/blob/master/gen_6_7/auto_layout_gen6_gen7.py . Decoder and tests are original implementations of those data formats. Synthetic tests do not validate a live address or Android gameplay.

Validation after resuming: nine focused 3DS unit tests pass (four Gen 6 decoder tests plus five header tests); the debug APK builds. The experimental checkbox and guide were inspected in emulator screenshots. The guide contrast is readable. Native memory access also checks that the core has a current page table before using GetPointer. Imports and engine starts now reject concurrent requests, and a start that finishes after the activity closes unloads the core. Hardware gameplay and live-memory addresses remain unverified.
