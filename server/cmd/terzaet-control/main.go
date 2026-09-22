package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"strings"

	"openflux/control"
)

func main() {
	listen := flag.String("listen", "0.0.0.0:8787", "local control address")
	data := flag.String("data", "/opt/terzaet/control/users.json", "users database")
	secret := flag.String("secret", "/opt/terzaet/control/signing.key", "bundle signing secret")
	tokenFile := flag.String("token-file", "/opt/terzaet/control/admin.token", "admin token file")
	flag.Parse()
	token, err := os.ReadFile(*tokenFile)
	if err != nil || len(strings.TrimSpace(string(token))) < 24 { log.Fatal("admin token file is missing or too short") }
	store, err := control.Open(*data, *secret); if err != nil { log.Fatal(err) }
	log.Printf("TERZAET control API listening on %s", *listen)
	if err := http.ListenAndServe(*listen, control.NewServer(store, strings.TrimSpace(string(token))).Handler()); err != nil { log.Fatal(err) }
}
