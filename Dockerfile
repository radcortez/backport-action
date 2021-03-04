FROM quay.io/maxandersen/jbang-action

ADD backport.java /backport.java

ENTRYPOINT ["/jbang/bin/jbang", "/backport.java"]
