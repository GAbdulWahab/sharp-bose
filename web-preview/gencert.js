const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// Pure Node.js self-signed X.509 certificate generator using crypto module
function generateSelfSignedCert() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicKeyEncoding: { type: 'pkcs1', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs1', format: 'pem' }
  });

  // Extract public key DER
  const pubDer = crypto.createPublicKey(publicKey).export({ type: 'spki', format: 'der' });
  const privKeyObj = crypto.createPrivateKey(privateKey);

  // Helper to create ASN.1 DER length
  function encodeLength(len) {
    if (len < 128) return Buffer.from([len]);
    const bytes = [];
    let temp = len;
    while (temp > 0) {
      bytes.unshift(temp & 0xff);
      temp >>= 8;
    }
    return Buffer.from([0x80 | bytes.length, ...bytes]);
  }

  function asn1(tag, data) {
    const d = Buffer.isBuffer(data) ? data : Buffer.concat(data);
    return Buffer.concat([Buffer.from([tag]), encodeLength(d.length), d]);
  }

  const SEQUENCE = 0x30;
  const SET = 0x31;
  const INTEGER = 0x02;
  const BIT_STRING = 0x03;
  const OCTET_STRING = 0x04;
  const OBJECT_IDENTIFIER = 0x06;
  const UTCTIME = 0x17;
  const PRINTABLE_STRING = 0x13;
  const UTF8_STRING = 0x0c;
  const CONTEXT_0 = 0xa0;
  const CONTEXT_3 = 0xa3;

  // Serial Number: 1
  const serial = asn1(INTEGER, Buffer.from([0x01]));

  // Signature Algorithm: sha256WithRSAEncryption (1.2.840.113549.1.1.11)
  const sha256WithRSA = asn1(SEQUENCE, [
    asn1(OBJECT_IDENTIFIER, Buffer.from([0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b])),
    Buffer.from([0x05, 0x00]) // NULL
  ]);

  // Issuer / Subject: CN=OfflineMesh, O=Offline Mesh Intercom
  function createDN() {
    const cn = asn1(SET, [
      asn1(SEQUENCE, [
        asn1(OBJECT_IDENTIFIER, Buffer.from([0x55, 0x04, 0x03])), // commonName
        asn1(UTF8_STRING, Buffer.from('OfflineMesh Live Call'))
      ])
    ]);
    return asn1(SEQUENCE, [cn]);
  }

  const issuer = createDN();
  const subject = createDN();

  // Validity: 2026-01-01 to 2036-01-01
  const notBefore = asn1(UTCTIME, Buffer.from('260101000000Z'));
  const notAfter = asn1(UTCTIME, Buffer.from('360101000000Z'));
  const validity = asn1(SEQUENCE, [notBefore, notAfter]);

  // Extensions: Subject Alternative Name (SAN): DNS:localhost, IP:10.73.88.166, IP:127.0.0.1
  // IP 10.73.88.166 = [0x0a, 0x49, 0x58, 0xa6], 127.0.0.1 = [0x7f, 0x00, 0x00, 0x01]
  const sanEntries = Buffer.concat([
    Buffer.concat([Buffer.from([0x82, 0x09]), Buffer.from('localhost')]), // DNS: localhost
    Buffer.concat([Buffer.from([0x87, 0x04]), Buffer.from([10, 73, 88, 166])]), // IP: 10.73.88.166
    Buffer.concat([Buffer.from([0x87, 0x04]), Buffer.from([127, 0, 0, 1])]) // IP: 127.0.0.1
  ]);
  const sanSequence = asn1(SEQUENCE, sanEntries);
  const extSAN = asn1(SEQUENCE, [
    asn1(OBJECT_IDENTIFIER, Buffer.from([0x55, 0x1d, 0x11])), // id-ce-subjectAltName (2.5.29.17)
    asn1(OCTET_STRING, sanSequence)
  ]);
  const extensions = asn1(CONTEXT_3, [asn1(SEQUENCE, [extSAN])]);

  // TBS Certificate
  const version = asn1(CONTEXT_0, [asn1(INTEGER, Buffer.from([0x02]))]); // v3 (value 2)
  const tbsCertificate = asn1(SEQUENCE, [
    version,
    serial,
    sha256WithRSA,
    issuer,
    validity,
    subject,
    pubDer,
    extensions
  ]);

  // Sign TBS Certificate with RSA private key
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(tbsCertificate);
  const signature = signer.sign(privKeyObj);

  // Final X.509 Certificate DER
  const certDer = asn1(SEQUENCE, [
    tbsCertificate,
    sha256WithRSA,
    asn1(BIT_STRING, Buffer.concat([Buffer.from([0x00]), signature]))
  ]);

  const certPem = `-----BEGIN CERTIFICATE-----\n${certDer.toString('base64').match(/.{1,64}/g).join('\n')}\n-----END CERTIFICATE-----\n`;

  return {
    key: privateKey,
    cert: certPem
  };
}

module.exports = { generateSelfSignedCert };

if (require.main === module) {
  const { key, cert } = generateSelfSignedCert();
  console.log('Certificate generated successfully!');
  console.log(cert.substring(0, 100) + '...');
}
