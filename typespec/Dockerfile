FROM nginx:alpine

# Copy the index.html and openapi.yaml to the Nginx html directory
COPY index.html /usr/share/nginx/html/
COPY tsp-output/@typespec/openapi3/openapi.yaml /usr/share/nginx/html/tsp-output/@typespec/openapi3/

# Expose port 9080
EXPOSE 9080

CMD echo "server { \
        listen 9080; \
        location /openapi { \
            index /usr/share/nginx/html; \
        } \
    }" > /etc/nginx/conf.d/default.conf && \
    nginx -g 'daemon off;'
