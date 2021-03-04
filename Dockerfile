FROM quay.io/jbangdev/jbang-action

ADD *.java /

ENTRYPOINT ["/jbang/bin/jbang", "/backportAction.java"]
