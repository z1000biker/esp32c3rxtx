//go:build windows

package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"errors"
	"io/fs"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"
)

//go:embed web
var assets embed.FS

func main() {
	if err := run(); err != nil {
		msg, _ := syscall.UTF16PtrFromString(err.Error())
		title, _ := syscall.UTF16PtrFromString("ESP32-C6 Audio Workbench")
		syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(
			0, uintptr(unsafe.Pointer(msg)), uintptr(unsafe.Pointer(title)), 0x10,
		)
	}
}

func browserPath() (string, error) {
	for _, browser := range []string{
		"Microsoft/Edge/Application/msedge.exe",
		"Google/Chrome/Application/chrome.exe",
	} {
		for _, env := range []string{"ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA"} {
			base := os.Getenv(env)
			if base == "" {
				continue
			}
			path := filepath.Join(base, filepath.FromSlash(browser))
			if info, err := os.Stat(path); err == nil && !info.IsDir() {
				return path, nil
			}
		}
	}
	return "", errors.New("Microsoft Edge or Google Chrome is required for USB access")
}

func run() error {
	browser, err := browserPath()
	if err != nil {
		return err
	}
	profile, err := os.MkdirTemp("", "c6-workbench-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(profile)

	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()

	token := make([]byte, 16)
	if _, err := rand.Read(token); err != nil {
		return err
	}
	prefix := "/" + hex.EncodeToString(token) + "/"
	web, err := fs.Sub(assets, "web")
	if err != nil {
		return err
	}
	files := http.StripPrefix(prefix, http.FileServer(http.FS(web)))
	handler := http.NewServeMux()
	handler.Handle(prefix, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Permissions-Policy", "serial=(self)")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; object-src 'none'; frame-ancestors 'none'")
		files.ServeHTTP(w, r)
	}))
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second}
	defer server.Close()
	go server.Serve(listener)

	url := "http://" + listener.Addr().String() + prefix
	return exec.Command(browser, "--app="+url, "--user-data-dir="+profile,
		"--no-first-run", "--no-default-browser-check", "--disable-background-mode").Run()
}
