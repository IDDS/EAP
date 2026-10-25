FROM @RUNTIME_IMAGE@
RUN rm -rf /usr/share/nginx/html && mkdir -p /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html
EXPOSE 80
