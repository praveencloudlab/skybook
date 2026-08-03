# Deploying SkyBook on Oracle Cloud (Always Free), 24/7 and £0

The only free tier big enough for this stack (10 app containers + Kafka +
Postgres + observability) is Oracle's **Always Free Ampere A1**: an ARM VM
with up to 4 OCPUs and 24 GB RAM that never expires. This guide takes a
fresh Oracle account to a public HTTPS URL.

What you end up with:

```
you (anywhere) ──HTTPS──> Caddy (VM :443, auto-TLS)
                             └─> frontend nginx ──/api──> api-gateway ──> services
Grafana / Prometheus / Mailpit / raw gateway: 127.0.0.1 on the VM only
(reach them through an SSH tunnel when needed)
```

---

## 1. Create the VM (browser, ~15 min)

1. Sign up at <https://www.oracle.com/cloud/free/>. A card is required for
   identity verification; Always Free resources never charge it. Pick your
   **home region** carefully - it cannot change later, and A1 capacity
   varies by region (see the note below).
2. Console → Compute → Instances → **Create instance**:
   - Image: **Ubuntu 24.04** (aarch64)
   - Shape: **Ampere → VM.Standard.A1.Flex**, set **4 OCPU / 24 GB** (the
     full free allowance - use all of it)
   - Add your SSH public key (or download the generated one)
   - Boot volume: 100 GB+ (free allowance is 200 GB total)
3. **"Out of capacity" error?** A1 shapes are popular. Retry at odd hours,
   try a different availability domain, or reduce to 2 OCPU / 12 GB (still
   plenty). Persistence wins within a day or two.
4. Networking → your instance's subnet → **Security List** → Add ingress
   rules for TCP **80** and **443** from `0.0.0.0/0` (22 is already open).

## 2. Prepare the VM (SSH, ~10 min)

```bash
ssh ubuntu@<VM_PUBLIC_IP>

# Docker + compose plugin
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit   # re-SSH so the group applies
```

Ubuntu's own firewall on Oracle images also filters ports - open them there
too (this bites everyone once):

```bash
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 3. Free domain (2 min)

1. <https://www.duckdns.org> → sign in → create a subdomain, e.g.
   `skybook-praveen` → set its IP to the VM's public IP.
2. Your URL is `https://skybook-praveen.duckdns.org`. (If the VM's IP ever
   changes, update it on the DuckDNS page - one field.)

## 4. Deploy (~20 min, mostly image builds)

```bash
git clone https://github.com/praveencloudlab/skybook.git && cd skybook
cp env.example .env
nano .env
```

Fill EVERY value in `.env` with fresh production secrets - compose refuses
to start with placeholders. Generate them on the VM:

```bash
# RS256 keypair for JWTs
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt_private.pem
openssl pkey -in jwt_private.pem -pubout -out jwt_public.pem
grep -v -- --- jwt_private.pem | tr -d '\n'   # -> JWT_PRIVATE_KEY
grep -v -- --- jwt_public.pem  | tr -d '\n'   # -> JWT_PUBLIC_KEY
rm jwt_private.pem jwt_public.pem

# One per secret: POSTGRES_PASSWORD, the four *_CLIENT_SECRETs,
# CHECKIN_BOARDING_PASS_KEY, GRAFANA_ADMIN_PASSWORD
openssl rand -base64 32
```

Two additions beyond `env.example`:

```bash
echo 'SKYBOOK_DOMAIN=skybook-praveen.duckdns.org' >> .env
# Real outbound email (Gmail app password: myaccount.google.com/apppasswords).
# Leave MAIL_* pointing at Mailpit instead if you'd rather emails stay
# internal - view them via the SSH tunnel below.
```

Then:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

The VM is ARM; every base image in the stack (Temurin, nginx, node,
Postgres, Kafka, the observability images) has an arm64 variant, so the
build is the same command as on your laptop - just give the first one
15-20 minutes.

Seed the flight schedule once the services are healthy:

```bash
./scripts/seed/seed.sh    # or seed_mesh.sh for the full-mesh schedule
```

## 5. Verify

- `https://<your-domain>` loads the app with a valid padlock (Caddy
  obtained the certificate automatically - if not, re-check step 2's
  iptables and the security-list rules for port 80).
- Register an account, search, book - from your phone, laptop off.

## 6. Post-deploy hardening checklist

- [ ] Do NOT create the demo/test users from the docs; register your own.
- [ ] Set `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` in `.env` to your own registered
      account, `docker compose up -d auth-service` once, then blank it out.
- [ ] Confirm nothing but 80/443/22 answers from outside:
      `nmap <VM_IP>` from your laptop should show exactly those.
- [ ] Grafana/Prometheus/Mailpit stay VM-local. Reach them when needed:
      `ssh -L 3001:localhost:3001 -L 8025:localhost:8025 ubuntu@<VM_IP>`
      then open `http://localhost:3001` (Grafana) / `:8025` (Mailpit).

## 7. Operating it

```bash
# update to latest main
git pull && docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# logs / status
docker compose ps
docker compose logs -f --tail 100 api-gateway

# everything restarts by itself after a VM reboot (restart: unless-stopped
# on the public-facing services; Docker's default policies elsewhere).
```

Costs: £0 as long as the instance stays on Always Free shapes. Oracle
reclaims IDLE Always Free instances after 7 days below ~15% CPU on some
accounts - this stack's JVMs and sweeps keep it comfortably above that.
