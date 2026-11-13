// HTMOD Windows : wabridge.exe, pont WhatsApp de HTMod (bibliotheque libre whatsmeow, gratuite, sans
// service tiers). Le PC est relie au compte WhatsApp de l'utilisateur comme WhatsApp Web (QR code a
// scanner une fois dans WhatsApp > Appareils connectes) ; la session est gardee dans <dossier>\session.db.
//
//   wabridge.exe <dossier>
//
// Commandes (une ligne JSON par commande sur l'entree standard) :
//   {"cmd":"login"}                          QR code (fichier PNG) puis resultat de la liaison
//   {"cmd":"state"}                          etat de la liaison
//   {"cmd":"groups"}                         groupes du compte
//   {"cmd":"send","to":"…@g.us","text":"…","tag":"…"}   envoi d'un message texte
//   {"cmd":"logout"}                         deliaison du PC
// Evenements (une ligne JSON par evenement sur la sortie standard) :
//   {"ev":"state","linked":"1","connected":"1","jid":"…"}  {"ev":"qr","png":"…"}  {"ev":"login","result":"success|timeout|…"}
//   {"ev":"group","id":"…@g.us","name":"…"}  {"ev":"groupsEnd","count":"…"}
//   {"ev":"sent","tag":"…"}  {"ev":"error","tag":"…","msg":"…"}  {"ev":"loggedOut"}
// Fin de l'entree standard (HTMod ferme) : deconnexion et sortie.
package main

import (
	"bufio"
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"sync"

	"github.com/skip2/go-qrcode"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
	_ "modernc.org/sqlite"
)

var (
	outMu sync.Mutex
	enc   = json.NewEncoder(os.Stdout)
)

func emit(kv ...string) {
	m := map[string]string{}
	for i := 0; i+1 < len(kv); i += 2 {
		m[kv[i]] = kv[i+1]
	}
	outMu.Lock()
	_ = enc.Encode(m)
	outMu.Unlock()
}

func b(v bool) string {
	if v {
		return "1"
	}
	return "0"
}

type command struct {
	Cmd  string `json:"cmd"`
	To   string `json:"to"`
	Text string `json:"text"`
	Tag  string `json:"tag"`
}

func main() {
	enc.SetEscapeHTML(false)
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage : wabridge.exe <dossier>")
		os.Exit(2)
	}
	dir := os.Args[1]
	_ = os.MkdirAll(dir, 0o700)
	ctx := context.Background()

	db, err := sql.Open("sqlite", "file:"+filepath.Join(dir, "session.db")+"?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)")
	if err != nil {
		emit("ev", "error", "msg", "base de session : "+err.Error())
		os.Exit(1)
	}
	container := sqlstore.NewWithDB(db, "sqlite3", waLog.Noop)
	if err := container.Upgrade(ctx); err != nil {
		emit("ev", "error", "msg", "base de session : "+err.Error())
		os.Exit(1)
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		emit("ev", "error", "msg", "session : "+err.Error())
		os.Exit(1)
	}
	client := whatsmeow.NewClient(device, waLog.Noop)

	state := func() {
		jid := ""
		if client.Store.ID != nil {
			jid = client.Store.ID.User
		}
		emit("ev", "state", "linked", b(client.Store.ID != nil), "connected", b(client.IsConnected() && client.IsLoggedIn()), "jid", jid)
	}
	client.AddEventHandler(func(evt interface{}) {
		switch evt.(type) {
		case *events.Connected, *events.Disconnected, *events.PairSuccess:
			state()
		case *events.LoggedOut:
			emit("ev", "loggedOut")
			state()
		}
	})
	if client.Store.ID != nil {
		if err := client.Connect(); err != nil {
			emit("ev", "error", "msg", "connexion : "+err.Error())
		}
	}
	state()

	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		var c command
		line := bytes.TrimPrefix(sc.Bytes(), []byte("\xef\xbb\xbf")) // marque BOM eventuelle
		if len(bytes.TrimSpace(line)) == 0 {
			continue
		}
		if err := json.Unmarshal(line, &c); err != nil {
			emit("ev", "error", "msg", "commande illisible")
			continue
		}
		switch c.Cmd {
		case "state":
			state()
		case "login":
			if client.Store.ID != nil {
				if !client.IsConnected() {
					_ = client.Connect()
				}
				state()
				continue
			}
			client.Disconnect()
			qr, err := client.GetQRChannel(ctx)
			if err != nil {
				emit("ev", "error", "msg", "QR : "+err.Error())
				continue
			}
			if err := client.Connect(); err != nil {
				emit("ev", "error", "msg", "connexion : "+err.Error())
				continue
			}
			go func() {
				n := 0
				for item := range qr {
					if item.Event == "code" {
						n++
						p := filepath.Join(dir, "qr-"+strconv.Itoa(n)+".png")
						if err := qrcode.WriteFile(item.Code, qrcode.Medium, 360, p); err != nil {
							emit("ev", "error", "msg", "QR : "+err.Error())
							continue
						}
						emit("ev", "qr", "png", p)
					} else {
						emit("ev", "login", "result", item.Event)
					}
				}
			}()
		case "groups":
			gs, err := client.GetJoinedGroups(ctx)
			if err != nil {
				emit("ev", "error", "tag", "groups", "msg", err.Error())
				continue
			}
			for _, g := range gs {
				emit("ev", "group", "id", g.JID.String(), "name", g.Name)
			}
			emit("ev", "groupsEnd", "count", strconv.Itoa(len(gs)))
		case "send":
			if client.Store.ID == nil {
				emit("ev", "error", "tag", c.Tag, "msg", "PC non relié à WhatsApp (RELIER dans les options)")
				continue
			}
			to, err := types.ParseJID(c.To)
			if err != nil {
				emit("ev", "error", "tag", c.Tag, "msg", "destinataire invalide : "+c.To)
				continue
			}
			if !client.IsConnected() {
				_ = client.Connect()
			}
			_, err = client.SendMessage(ctx, to, &waE2E.Message{Conversation: proto.String(c.Text)})
			if err != nil {
				emit("ev", "error", "tag", c.Tag, "msg", err.Error())
				continue
			}
			emit("ev", "sent", "tag", c.Tag)
		case "logout":
			if client.Store.ID != nil {
				if err := client.Logout(ctx); err != nil {
					emit("ev", "error", "msg", "déliaison : "+err.Error())
				}
			}
			state()
		default:
			emit("ev", "error", "msg", "commande inconnue : "+c.Cmd)
		}
	}
	client.Disconnect()
}
