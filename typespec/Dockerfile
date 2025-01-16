FROM nginx:alpine

COPY index.html /usr/share/nginx/html/
COPY tsp-output/@typespec/openapi3/openapi.yaml /usr/share/nginx/html/tsp-output/@typespec/openapi3/

# Expose port 9080
EXPOSE 9080

CMD echo "server { \
        listen 9080; \
        location /openapi { \
                root /usr/share/nginx/html; \
                index index.html; \
                try_files /index.html =404; \
            } \
        root /usr/share/nginx/html; \
    }" >> /etc/nginx/conf.d/default.conf && \
    nginx -g 'daemon off;'
