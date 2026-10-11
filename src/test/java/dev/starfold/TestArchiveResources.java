package dev.starfold;
/** Read original dependency bytes: JPMS hides .class/client resources from the transforming loader. */
final class TestArchiveResources {
    static java.io.InputStream open(String path)throws java.io.IOException{
        String name=path.startsWith("/")?path.substring(1):path;
        var local=TestArchiveResources.class.getResourceAsStream("/"+name);
        if(local!=null)return local;
        for(String archive:System.getProperty("starfold.testArchives").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))try(var zip=new java.util.zip.ZipFile(archive)){
            var entry=zip.getEntry(name);if(entry!=null)try(var in=zip.getInputStream(entry)){return new java.io.ByteArrayInputStream(in.readAllBytes());}
        }
        return null;
    }
}
