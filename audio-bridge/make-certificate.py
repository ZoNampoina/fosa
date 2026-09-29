"""Create a private local CA and a LAN server certificate. Never installs trust."""
import argparse
import datetime
import ipaddress
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

p = argparse.ArgumentParser()
p.add_argument('--ip', required=True)
a = p.parse_args()
ip = ipaddress.ip_address(a.ip)
if not ip.is_private:
    p.error('Utilise une adresse IP privée du réseau local.')
out = Path(__file__).parent/'certs'
out.mkdir(exist_ok=True)
now = datetime.datetime.now(datetime.timezone.utc)
keyfile = out/'fosa-local-ca.key'
certfile = out/'fosa-local-ca.crt'
if keyfile.exists() and certfile.exists():
    ca_key = serialization.load_pem_private_key(keyfile.read_bytes(), password=None)
    ca = x509.load_pem_x509_certificate(certfile.read_bytes())
else:
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'FOSA Local CA')])
    ca = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5))
          .not_valid_after(now+datetime.timedelta(days=3650)).add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
          .sign(ca_key, hashes.SHA256()))
    keyfile.write_bytes(ca_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    certfile.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, str(ip))]))
        .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=365))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ip), x509.IPAddress(ipaddress.ip_address('127.0.0.1')), x509.DNSName('localhost')]), critical=False)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False).sign(ca_key, hashes.SHA256()))
(out/'server.key').write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
(out/'server.crt').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
print('Certificat créé pour', ip)
print('À transférer : certs/fosa-local-ca.crt seulement. Les fichiers .key restent privés sur le PC.')
