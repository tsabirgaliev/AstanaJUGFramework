package framework;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import framework.api.Path;
import framework.api.QueryParam;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;

public class Main {
    public static void main(String[] args) throws Exception {

        concat( of(1)
              , concat( of(2,3,4,5)
                      , of(6) ) )
              .forEach(System.out::println);

        System.exit(0);

        File file = new File("./application/build/libs/application.jar");
        JarFile jarFile = new JarFile(file);

        URLClassLoader classLoader = new URLClassLoader(new URL[]{file.toURI().toURL()});

        Map<String, Method> methodsByPath = jarFile.stream()
                .filter(e -> e.getName().endsWith(".class"))
                .map(e -> e.getName())
                .map(name -> name.substring(0, name.length() - ".class".length()))
                .map(name -> name.replace('/', '.'))
                .peek(System.out::println)
                .map(name -> loadClass(classLoader, name))
                .flatMap(clazz -> Stream.of(clazz.getMethods()))
                .filter(method -> method.getAnnotation(Path.class) != null)
                .filter(method ->
                        Stream.of(method.getParameters())
                                .allMatch(p -> p.getAnnotation(QueryParam.class) != null)
                ).collect(
                        toMap(
                                m -> m.getAnnotation(Path.class).value(),
                                m -> m,
                                (a, b) -> {
                                    throw new RuntimeException(String.format("Methods %s and %b have same @Path", a, b));
                                }
                        )
                );

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new Framework(methodsByPath)::handle);
        server.start();
    }

    static Map<String, Method> getMethodsByPath(ClassLoader loader, JarFile archive) {
        List<Class> supportedParamTypes = Arrays.asList(
                Double.class,
                Boolean.class,
                String.class,
                Enum.class
        );

        return archive.stream()
            .map(JarEntry::getName)
            .filter(name -> name.endsWith(".class"))
            .map(name -> name.replaceAll("\\.class$", ""))
            .map(name -> name.replace('/', '.'))
            .map(name -> loadClass(loader, name))
            .filter(Main::hasNullConstructor)
            .map(Class::getMethods)
            .flatMap(Stream::of)
            .filter(m -> m.getAnnotation(Path.class) != null)
            .filter(m ->
                Stream.of(m.getParameters())
                .allMatch(p ->
                    p.getAnnotation(QueryParam.class) != null
                    && supportedParamTypes.contains(p.getType())
                )
            )
            .collect(
                toMap(
                    m -> m.getAnnotation(Path.class).value(),
                    Function.identity(),
                    (a, b) -> {
                        throw new RuntimeException(String.format("Methods %s and %b have same @Path", a, b));
                    }
                )
            );
    }

    static boolean hasNullConstructor(Class c) {
        try {
            return c.getConstructor() != null;
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    static Class loadClass(ClassLoader loader, String name) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class: " + name, e);
        }
    }

    @SuppressWarnings("unused")
    String notFound() {
        return "Not found";
    }

    static class Framework {
        Map<String, Method> methodByPath;

        public Framework(Map<String, Method> methodByPath) {
            this.methodByPath = methodByPath;
        }

        public void handle(HttpExchange exchange) {
            try {
                URI uri = exchange.getRequestURI();
                String path = uri.getPath();
                String query = uri.getQuery();
                Map<String, List<String>> queryParams = queryParams(query);

                Method method = methodByPath.getOrDefault(path, notFound);

                Object[] arguments = Stream.of(method.getParameters())
                    .map(p -> {
                        Class type = p.getType();
                        String name = p.getAnnotation(QueryParam.class).value();
                        List<String> values = queryParams.getOrDefault(name, Collections.emptyList());

                        String value = values.stream()
                            .findFirst()
                            .orElseThrow(() -> new RuntimeException("Param " + name + " not found!"));

                        if (Double.class.equals(type)) {
                            return Double.valueOf(value);
                        } else {
                            return value;
                        }
                    }).toArray();
                ;

                Object result = method.invoke(
                    method.getDeclaringClass().newInstance(),
                    arguments
                    );

                outputResult(exchange, result);
            } catch (Exception e) {
                outputException(exchange, e);
            }

        }

        final static Method notFound;

        static {
            try {
                notFound = Main.class.getDeclaredMethod("notFound");
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        static Map<String, List<String>> queryParams(String query) {
            return Stream.of(Optional.ofNullable(query).orElse(""))
                    .flatMap(q -> Stream.of(q.split("&")))
                    .map(keyVal -> keyVal.split("="))
                    .filter(pair -> pair.length == 2)
                    .collect(groupingBy(pair -> decode(pair[0]), mapping(pair -> decode(pair[1]), toList())));
        }

        static void outputResult(HttpExchange exchange, Object result) throws IOException {
            byte[] resultBytes = String.valueOf(result).getBytes("UTF-8");
            exchange.sendResponseHeaders(200, resultBytes.length);
            OutputStream outputStream = exchange.getResponseBody();
            outputStream.write(resultBytes);
            outputStream.close();
        }

        static void outputException(HttpExchange exchange, Exception e) {
            try {
                StringWriter writer = new StringWriter();
                e.printStackTrace(new PrintWriter(writer));
                byte[] resultBytes = writer.toString().getBytes("UTF-8");
                exchange.sendResponseHeaders(500, resultBytes.length);
                OutputStream outputStream = exchange.getResponseBody();
                outputStream.write(resultBytes);
                outputStream.close();
            } catch (UnsupportedEncodingException e1) {
                e1.printStackTrace();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }

        static String decode(String encoded) {
            try {
                return URLDecoder.decode(encoded, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
