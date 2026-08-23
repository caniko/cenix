{
  description = "Cenix — installable GrapheneOS-focused HOME launcher";

  inputs = {
    harbor-rs.url = "git+https://github.com/caniko/harbor-rs.git?ref=trunk&rev=35ebc37423ff391e117cf4417390e4b862e48cdc";
    harbor-android.url = "git+https://github.com/caniko/harbor-android.git?ref=trunk&rev=751a9fcc896afa764b690cd0711c80decbdb7173";

    nixpkgs.follows = "harbor-rs/nixpkgs";
    rust-overlay.follows = "harbor-rs/rust-overlay";
    crane.follows = "harbor-rs/crane";
  };

  outputs = {
    self,
    nixpkgs,
    harbor-rs,
    harbor-android,
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
      toolchain = harbor-rs.lib.mkToolchain {
        inherit pkgs;
        toolchainFile = ./rust-toolchain.toml;
      };
      inherit (toolchain) craneLib rustToolchain;
      cross = harbor-rs.lib.mkCross {inherit pkgs system;};
      src = craneLib.cleanCargoSource ./.;
      cargoArtifacts = craneLib.buildDepsOnly {
        inherit src;
        pname = "cenix";
        version = "0.1.0";
      };
      androidComposition = harbor-android.lib.mkAndroidSdk {
        inherit pkgs;
        platformVersions = [androidPlatform];
        buildToolsVersions = ["35.0.0"];
        ndkVersions = [androidNdkVersion];
        includeNDK = true;
      };
      androidSdk = androidComposition.androidsdk or androidComposition;
      emulatorComposition = harbor-android.lib.mkAndroidSdk {
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
      rustShells = harbor-rs.lib.mkDevShells {
        inherit pkgs craneLib cross;
      };
      apkDebug = harbor-android.lib.mkAndroidApk {
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
        buildCommand = "nix build .#apk-debug";
      };
    in {
      inherit pkgs toolchain craneLib rustToolchain cargoArtifacts androidSdk emulatorSdk rustShells apkDebug;
      checks = {
        fmt = craneLib.cargoFmt {inherit src;};
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
      apk-debug = cfg.apkDebug;
    });

    checks = nixpkgs.lib.genAttrs systems (system: (forSystem system).checks);

    devShells = nixpkgs.lib.genAttrs systems (
      system: let
        cfg = forSystem system;
      in {
        default = harbor-android.lib.mkAndroidDevShell {
          inherit (cfg) pkgs;
          androidSdk = cfg.emulatorSdk;
          ndkVersion = androidNdkVersion;
          rustToolchain = cfg.rustToolchain;
          base = cfg.rustShells.default;
          extraPackages = [cfg.pkgs.aapt cfg.pkgs.android-tools];
        };
      }
    );
  };
}
