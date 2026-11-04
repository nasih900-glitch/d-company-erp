package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"math/big"
	"strings"
	"testing"
	"time"
)

func TestRejectNilOrEmptyPools(t *testing.T) {
	for _, tc := range []struct {
		name string
		pool *x509.CertPool
	}{
		{"nil", nil}, {"empty", x509.NewCertPool()},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := verifyPool(tc.pool); !errors.Is(err, errEmptyPool) {
				t.Fatalf("got %v; want fail-closed empty pool", err)
			}
		})
	}
}

func TestRejectTrustedDistrustedRoot(t *testing.T) {
	cert, err := referenceCertificate()
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(cert)
	if err := verifyPool(roots); !errors.Is(err, errDistrustedRootTrusted) {
		t.Fatalf("trusted exact reference must fail gate; got %v", err)
	}
}

func TestAcceptUnrelatedPool(t *testing.T) {
	// Throwaway local test CA only. No release/application signing key is used.
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Unrelated unit-test CA"},
		NotBefore: time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC),
		NotAfter:  time.Date(2040, 1, 1, 0, 0, 0, 0, time.UTC),
		IsCA:      true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(cert)
	if err := verifyPool(roots); err != nil {
		t.Fatalf("unrelated valid pool rejected: %v", err)
	}
}

func TestRejectEnvironmentOverrides(t *testing.T) {
	t.Setenv("SSL_CERT_FILE", "")
	t.Setenv("SSL_CERT_DIR", "")
	for _, key := range []string{"SSL_CERT_FILE", "SSL_CERT_DIR"} {
		t.Run(key, func(t *testing.T) {
			t.Setenv(key, "/not-an-image-trust-store")
			if _, err := verifySystemRoots(); err == nil || !strings.Contains(err.Error(), "unexpected trust override") {
				t.Fatalf("override must fail before system trust loading; got %v", err)
			}
		})
	}
}
