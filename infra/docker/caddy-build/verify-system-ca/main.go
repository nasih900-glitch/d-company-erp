// Command verify-system-ca checks the effective Go trust pool used by Caddy.
// It runs only during the image build and is removed from the final filesystem.
package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
)

const distrustedRootSHA256 = "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b"

// Public emSign ECC Root CA - G3 from the previous signed Alpine CA package.
// The exact DER fingerprint is pinned above; this is not an application key.
const distrustedRootPEM = `-----BEGIN CERTIFICATE-----
MIICTjCCAdOgAwIBAgIKPPYHqWhwDtqLhDAKBggqhkjOPQQDAzBrMQswCQYDVQQG
EwJJTjETMBEGA1UECxMKZW1TaWduIFBLSTElMCMGA1UEChMcZU11ZGhyYSBUZWNo
bm9sb2dpZXMgTGltaXRlZDEgMB4GA1UEAxMXZW1TaWduIEVDQyBSb290IENBIC0g
RzMwHhcNMTgwMjE4MTgzMDAwWhcNNDMwMjE4MTgzMDAwWjBrMQswCQYDVQQGEwJJ
TjETMBEGA1UECxMKZW1TaWduIFBLSTElMCMGA1UEChMcZU11ZGhyYSBUZWNobm9s
b2dpZXMgTGltaXRlZDEgMB4GA1UEAxMXZW1TaWduIEVDQyBSb290IENBIC0gRzMw
djAQBgcqhkjOPQIBBgUrgQQAIgNiAAQjpQy4LRL1KPOxst3iAhKAnjlfSU2fySU0
WXTsuwYc58Byr+iuL+FBVIcUqEqy6HyC5ltqtdyzdc6LBtCGI79G1Y4PPwT01xyS
fvalY8L1X44uT6EYGQIrMgqCZH0Wk9GjQjBAMB0GA1UdDgQWBBR8XQKEE9TMipuB
zhccLikenEhjQjAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAKBggq
hkjOPQQDAwNpADBmAjEAvvNhzwIQHWSVB7gYboiFBS+DCBeQyh+KTOgNG3qxrdWB
CUfvO6wIBHxcmbHtRwfSAjEAnbpV/KlK6O3t5nYBQnvI+GDZjVGLVTv7jHvrZQnD
+JbNR6iC8hZVdyR+EhCVBCyj
-----END CERTIFICATE-----`

var (
	errEmptyPool             = errors.New("effective Go root pool is empty")
	errDistrustedRootTrusted = errors.New("distrusted emSign root remains trusted")
)

func referenceCertificate() (*x509.Certificate, error) {
	block, rest := pem.Decode([]byte(distrustedRootPEM))
	if block == nil || block.Type != "CERTIFICATE" || len(bytes.TrimSpace(rest)) != 0 {
		return nil, errors.New("invalid pinned public reference certificate")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse pinned public reference: %w", err)
	}
	if fmt.Sprintf("%x", sha256.Sum256(cert.Raw)) != distrustedRootSHA256 {
		return nil, errors.New("pinned public reference DER fingerprint differs")
	}
	if !cert.IsCA || !cert.BasicConstraintsValid || !cert.NotAfter.After(cert.NotBefore) {
		return nil, errors.New("pinned public reference is not a valid CA")
	}
	if err := cert.CheckSignatureFrom(cert); err != nil {
		return nil, fmt.Errorf("pinned public reference is not self-signed: %w", err)
	}
	return cert, nil
}

func verifyPool(roots *x509.CertPool) error {
	if roots == nil || len(roots.Subjects()) == 0 {
		return errEmptyPool
	}
	cert, err := referenceCertificate()
	if err != nil {
		return err
	}
	// A midpoint inside this fixed certificate's validity avoids treating its
	// eventual expiry as proof that the root was removed. Any EKU is allowed.
	_, err = cert.Verify(x509.VerifyOptions{
		Roots:       roots,
		CurrentTime: cert.NotBefore.Add(cert.NotAfter.Sub(cert.NotBefore) / 2),
		KeyUsages:   []x509.ExtKeyUsage{x509.ExtKeyUsageAny},
	})
	if err == nil {
		return errDistrustedRootTrusted
	}
	var unknown x509.UnknownAuthorityError
	if !errors.As(err, &unknown) {
		return fmt.Errorf("reference verification failed for a reason other than root absence: %w", err)
	}
	return nil
}

func verifySystemRoots() (int, error) {
	for _, key := range []string{"SSL_CERT_FILE", "SSL_CERT_DIR"} {
		if os.Getenv(key) != "" {
			return 0, fmt.Errorf("unexpected trust override: %s", key)
		}
	}
	roots, err := x509.SystemCertPool()
	if err != nil {
		return 0, fmt.Errorf("load effective Go root pool: %w", err)
	}
	if err := verifyPool(roots); err != nil {
		return 0, err
	}
	return len(roots.Subjects()), nil
}

func main() {
	count, err := verifySystemRoots()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Caddy system CA verification failed:", err)
		os.Exit(1)
	}
	fmt.Printf("Verified effective Go CA pool: %d subjects; rejected DER SHA256 %s\n", count, distrustedRootSHA256)
}
