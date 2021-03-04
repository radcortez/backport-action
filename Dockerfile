FROM quay.io/maxandersen/jbang-action

ADD backport.java /backport.java
ADD backportAction.java /backportAction.java

ENTRYPOINT ["/jbang/bin/jbang", "/backportAction.java"]
