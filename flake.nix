{
  description = "mcp-toolkit — Clojure MCP server toolkit (Streamable HTTP, JVM + bb + CLJS)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix.url = "github:jlesquembre/clj-nix";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    clj-nix,
  }:
    flake-utils.lib.eachDefaultSystem (system: let
      pkgs = import nixpkgs {inherit system;};
    in {
      formatter = pkgs.alejandra;

      packages = {
        deps-lock = clj-nix.packages.${system}.deps-lock;
      };

      devShells.default = pkgs.mkShell {
        name = "mcp-toolkit-dev";

        buildInputs = with pkgs; [
          jdk21
          clojure
          babashka
          clj-kondo
          cljfmt
          jet
        ];

        shellHook = ''
          echo "mcp-toolkit Dev Environment"
          echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
          echo "Clojure: $(clojure -M --version 2>&1 | head -1)"
          echo "Babashka: $(bb --version)"
          echo "clj-kondo: $(clj-kondo --version)"
          echo ""
          echo "Quick commands:"
          echo "  bb test/mcp_toolkit/smoke_test.bb    Run babashka smoke tests"
          echo "  clojure -M:test                       Run JVM tests (kaocha)"
          echo "  clj-kondo --lint src                  Lint source"
          echo "  cljfmt fix src/                       Format source"
          echo "  nix run .#deps-lock                   Regenerate deps-lock.json"
          echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        '';
      };
    });
}
