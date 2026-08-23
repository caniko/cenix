{
  description = "Cenix — installable GrapheneOS-focused HOME launcher";

  inputs = {
    harbor-rs.url = "git+https://github.com/caniko/harbor-rs.git?ref=trunk&rev=35ebc37423ff391e117cf4417390e4b862e48cdc";
    harbor-js.url = "git+https://github.com/caniko/harbor-js.git?ref=trunk&rev=e18b16004cdfb2b27fbe1d681b4027a261e096a1";
    harbor-android.url = "git+https://github.com/caniko/harbor-android.git?ref=trunk&rev=751a9fcc896afa764b690cd0711c80decbdb7173";

    nixpkgs.follows = "harbor-rs/nixpkgs";
    rust-overlay.follows = "harbor-rs/rust-overlay";
    crane.follows = "harbor-rs/crane";
  };

  outputs = {
    self,
    nixpkgs,
    harbor-rs,
    harbor-js,
    harbor-android,
    rust-overlay,
    ...
  }: let
    systems = ["x86_64-linux" "aarch64-linux"];
    androidNdkVersion = "29.0.14206865";
    # GrapheneOS 17 tracks a newer platform than nixpkgs androidenv currently
    # ships. compile/target stay on API 35 until that SDK is available.
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
      bunToolchain = harbor-js.lib.mkBunToolchain {
        inherit pkgs;
        packageJson = ./tools/package.json;
      };
      androidComposition = harbor-android.lib.mkAndroidSdk {
        inherit pkgs;
        platformVersions = [androidPlatform];
        buildToolsVersions = ["35.0.0"];
        ndkVersions = [androidNdkVersion];
        includeNDK = true;
      };
      androidSdk = androidComposition.androidsdk or androidComposition;
      rustShells = harbor-rs.lib.mkDevShells {
        inherit pkgs craneLib cross;
        packages = bunToolchain.packages;
      };
    in {
      inherit pkgs toolchain craneLib rustToolchain cargoArtifacts bunToolchain androidSdk rustShells;
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
    packages = nixpkgs.lib.genAttrs systems (system: {
      default = (forSystem system).checks.test;
    });

    checks = nixpkgs.lib.genAttrs systems (system: (forSystem system).checks);

    devShells = nixpkgs.lib.genAttrs systems (
      system: let
        cfg = forSystem system;
      in {
        default = harbor-android.lib.mkAndroidDevShell {
          inherit (cfg) pkgs androidSdk;
          ndkVersion = androidNdkVersion;
          rustToolchain = cfg.rustToolchain;
          base = cfg.rustShells.default;
          extraPackages = cfg.bunToolchain.packages ++ [cfg.pkgs.aapt];
        };
      }
    );
  };
}
