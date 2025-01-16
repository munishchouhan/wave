FROM nginx:alpine

# Copy index.html and openapi.yaml to the Nginx html directory
COPY index.html /opt
COPY tsp-output/@typespec/openapi3/openapi.yaml /opt/tsp-output/@typespec/openapi3/

# Expose port 9080
EXPOSE 9080

CMD echo "server { \
        listen 9080; \
        location /openapi { \
            root /opt; \
            index index.html; \
            try_files /index.html =404; \
        } \
    }" > /etc/nginx/conf.d/default.conf && \
    nginx -g 'daemon off;'

