{
  description = "Hytale Minigame Framework - plugin development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          name = "hytale-minigame-framework-dev";

          packages = with pkgs; [
            jdk25

            # Language servers
            kotlin-language-server

            # Formatting
            ktfmt
          ];
          JAVA_HOME = pkgs.jdk25.home;
        };
      }
    );
}
