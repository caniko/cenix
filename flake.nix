{
  description = "Cenix — installable GrapheneOS-focused HOME launcher";

  inputs = {
    harbor.url = "git+https://github.com/caniko/harbor.git?ref=feat/harbor-monorepo-components&rev=e8b1996580e4ecd51dd402edf4a16d9df88e3a1b";

    nixpkgs.follows = "harbor/nixpkgs";
    rust-overlay.follows = "harbor/rust-overlay";
    crane.follows = "harbor/crane";
  };

  outputs = {
    self,
    nixpkgs,
    harbor,
    rust-overlay,
    ...
  }: let
    systems = ["x86_64-linux" "aarch64-linux"];
    androidNdkVersion = "29.0.14206865";
    androidPlatform = "35";
    forSystem = system: let
      pkgs = import nixpkgs {
        inherit system;
        overlays = [(import rust-overlay)];
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      toolchain = harbor.lib.rust.mkToolchain {
        inherit pkgs;
        toolchainFile = ./rust-toolchain.toml;
      };
      inherit (toolchain) craneLib rustToolchain;
      treefmtEval = harbor.inputs.treefmt-nix.lib.evalModule pkgs {
        imports = [
          harbor.treefmtModules.core-nix
          harbor.treefmtModules.core-toml
          harbor.treefmtModules.rust-rust
        ];
        projectRootFile = "flake.nix";
        programs.rustfmt.package = rustToolchain;
      };
      cross = harbor.lib.rust.mkCross {inherit pkgs system;};
      src = pkgs.lib.fileset.toSource {
        root = ./.;
        fileset = pkgs.lib.fileset.unions [
          (craneLib.fileset.commonCargoSources ./.)
          ./android/app/src/test/resources/backup
        ];
      };
      cargoArtifacts = craneLib.buildDepsOnly {
        inherit src;
        pname = "cenix";
        version = "0.1.0";
      };
      androidComposition = harbor.lib.android.mkAndroidSdk {
        inherit pkgs;
        platformVersions = [androidPlatform];
        buildToolsVersions = ["35.0.0"];
        ndkVersions = [androidNdkVersion];
        includeNDK = true;
      };
      androidSdk = androidComposition.androidsdk or androidComposition;
      emulatorComposition = harbor.lib.android.mkAndroidSdk {
        inherit pkgs;
        platformVersions = [androidPlatform];
        buildToolsVersions = ["35.0.0"];
        ndkVersions = [androidNdkVersion];
        includeNDK = true;
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = ["google_apis"];
        abiVersions = ["x86_64"];
      };
      emulatorSdk = emulatorComposition.androidsdk or emulatorComposition;
      emulatorAospComposition = harbor.lib.android.mkAndroidSdk {
        inherit pkgs;
        platformVersions = [androidPlatform];
        buildToolsVersions = ["35.0.0"];
        ndkVersions = [androidNdkVersion];
        includeNDK = true;
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = ["default"];
        abiVersions = ["x86_64"];
      };
      emulatorAospSdk = emulatorAospComposition.androidsdk or emulatorAospComposition;
      rustShells = harbor.lib.rust.mkDevShells {
        inherit pkgs craneLib cross;
        packages = [treefmtEval.config.build.wrapper];
      };
      # The SDK supplies its own build tools. Add the standalone nixpkgs
      # aapt only on platforms for which that package is available.
      devicePackages =
        [pkgs.android-tools pkgs.cargo-deny pkgs.diffoscope pkgs.reuse (harbor.lib.android.mkAndroidDeviceTools {inherit pkgs;})]
        ++ pkgs.lib.optional (pkgs.lib.meta.availableOn pkgs.stdenv.hostPlatform pkgs.aapt) pkgs.aapt;
      apkDebugBase = harbor.lib.android.mkAndroidApk {
        inherit pkgs androidSdk rustToolchain;
        workspaceSrc = ./.;
        cargoPkg = "cenix-ffi";
        gradleModule = ":app";
        jniLibsDir = "android/app/src/main/jniLibs";
        apkOutPath = "android/app/build/outputs/apk/debug/app-debug.apk";
        abi = "arm64-v8a";
        cargoNdkPlatform = 35;
        ndkVersion = androidNdkVersion;
        pname = "cenix-debug";
        buildCommand = "nix build .#apk-debug-arm64 --option sandbox false";
      };
      apkDebug = apkDebugBase.overrideAttrs (old: {
        CENIX_GIT_COMMIT = self.rev or self.dirtyRev or "unknown";
        preBuild =
          (old.preBuild or "")
          + ''
            export ANDROID_USER_HOME="$HOME/.android"
            mkdir -p "$ANDROID_USER_HOME"
          '';
      });
    in {
      inherit pkgs toolchain craneLib rustToolchain cargoArtifacts androidSdk emulatorSdk emulatorAospSdk rustShells apkDebug devicePackages treefmtEval;
      checks = {
        fmt = craneLib.cargoFmt {inherit src;};
        formatting = treefmtEval.config.build.check self;
        clippy = craneLib.cargoClippy {
          inherit src cargoArtifacts;
          cargoClippyExtraArgs = "--workspace --all-targets -- --deny warnings";
        };
        test = craneLib.cargoNextest {
          inherit src cargoArtifacts;
          cargoExtraArgs = "--workspace";
        };
      };
    };
  in {
    packages = nixpkgs.lib.genAttrs systems (system: let
      cfg = forSystem system;
    in {
      default = cfg.checks.test;
      apk-debug-arm64 = cfg.apkDebug;
    });

    checks = nixpkgs.lib.genAttrs systems (system: (forSystem system).checks);
    formatter = nixpkgs.lib.genAttrs systems (system: (forSystem system).treefmtEval.config.build.wrapper);

    devShells = nixpkgs.lib.genAttrs systems (
      system: let
        cfg = forSystem system;
      in {
        default = harbor.lib.android.mkAndroidDevShell {
          inherit (cfg) pkgs;
          inherit (cfg) androidSdk;
          ndkVersion = androidNdkVersion;
          inherit (cfg) rustToolchain;
          base = cfg.rustShells.default;
          extraPackages = cfg.devicePackages;
        };
        emulator = harbor.lib.android.mkAndroidDevShell {
          inherit (cfg) pkgs;
          androidSdk = cfg.emulatorSdk;
          ndkVersion = androidNdkVersion;
          inherit (cfg) rustToolchain;
          base = cfg.rustShells.default;
          extraPackages = cfg.devicePackages;
        };
        emulator-aosp = harbor.lib.android.mkAndroidDevShell {
          inherit (cfg) pkgs;
          androidSdk = cfg.emulatorAospSdk;
          ndkVersion = androidNdkVersion;
          inherit (cfg) rustToolchain;
          base = cfg.rustShells.default;
          extraPackages = cfg.devicePackages;
        };
      }
    );
  };
}
