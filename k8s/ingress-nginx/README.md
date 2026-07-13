# ingress-nginx (pinned copy)

`ingress-nginx-v1.12.1.yaml` is an unmodified copy of the official
`controller-v1.12.1` cloud deploy manifest, committed here so cluster
setup never depends on a live URL fetch (KUBERNETES_MODULE.md §2.6):

    https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/cloud/deploy.yaml

Apply once per cluster, before `kubectl apply -k k8s/base`:

    kubectl apply -f k8s/ingress-nginx/ingress-nginx-v1.12.1.yaml

On Docker Desktop its LoadBalancer Service binds to localhost - this is
the ONLY LoadBalancer in the whole cluster (trust boundary, design §9).
