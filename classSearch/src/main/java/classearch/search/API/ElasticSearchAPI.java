package classearch.search.API;

import classearch.search.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;


import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchPhraseQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ElasticSearchAPI {
    public static final String defaultINDEX = "classes";

    private final String defaultTerm = "default";

    // localHost credentials
    private static final String HOST = "localhost";
    private static final int PORT_ONE = 9200;
    private static final String SCHEME = "http";

    // cloud credentials
    private static final String USE_NAME = "elastic";
    private static final String PASSWORD = "M7vbKNXotX9fLKZqQpv5cfxq";
    private static final String END_URL = "94a45c0e05584bedaca8de4e7b5564b8.us-central1.gcp.cloud.es.io";

    private static RestHighLevelClient client;
    private String index;

    private static final int defaultSLOP = 10;


    private ElasticSearchAPI(RestHighLevelClient client){
        this.client = client;
    }

    private static ElasticSearchAPI of(RestHighLevelClient client){
        Objects.requireNonNull(client, "client can not be null");

        return new ElasticSearchAPI(client);
    }

    public boolean isConnected(){
        return this.client != null;
    }

    public static final ElasticSearchAPI makeConnectionLower(){
        if(client == null) {
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(USE_NAME, PASSWORD));
            RestClientBuilder restClientBuilder = RestClient.builder(
                    new HttpHost(END_URL, 9243,"https"))
                    .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                        @Override
                        public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpAsyncClientBuilder) {
                            return httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                        }
                    });
            client = new RestHighLevelClient(restClientBuilder);
        }
        return ElasticSearchAPI.of(client);
    }

    public static final ElasticSearchAPI makeConnection() {
        if(client == null) {
            try {
                System.out.println("making connection to elastic search");
                client = new RestHighLevelClient(RestClient.builder(
                        new HttpHost(HOST, PORT_ONE, SCHEME)
                ));
                System.out.println("connected to elastic search");
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        return ElasticSearchAPI.of(client);
    }

    public final void closeConnection(){
        if(client != null) {
            try {
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        client = null;
    }

    public List<Class> simpleMatchPhraseSearch(String field, String text, int slop) throws IOException {SearchRequest searchRequest = new SearchRequest(this.index);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(new MatchPhraseQueryBuilder(field, text).slop(slop));
        SearchResponse searchResponse = client.search(searchRequest.source(searchSourceBuilder), RequestOptions.DEFAULT);

        return classExtract(searchResponse.toString());
    }

    public List<Class> boolSearch(String index, String text) throws IOException {
        List<String> searchQuery = Arrays.asList(text.split(" "));

        Set<Class> result = new HashSet<>(
                                boolSearch(index, new ArrayList<>(Arrays.asList("Description", "Title", "Code")), text));

        for(int i = 0; i < searchQuery.size(); i++){
            String current = searchQuery.get(i);
            List<String> temp = new ArrayList<>(searchQuery);
            StringBuilder sb = new StringBuilder(current);

            if(sb.length() > 0) {
                if (current.charAt(current.length() - 1) != 's')
                    sb.append('s');
                else
                    sb.setLength(sb.length() - 1);
            }

            temp.set(i, sb.toString());
            boolSearch(index, new ArrayList<>(Arrays.asList("Description", "Title", "Code")), String.join(" ", temp))
                    .forEach(c -> result.add(c));

        }


        return new ArrayList<>(result);
    }



    public List<Class> boolSearch(String index, List<String> fields, String text) throws IOException {
        SearchRequest searchRequest = new SearchRequest(index);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        BoolQueryBuilder qb = QueryBuilders.boolQuery();

        for(String field: fields) {
            int boostFactor;

            switch (field){
                case "Code":
                    boostFactor = 3;
                    break;
                case "Title":
                    boostFactor = 2;
                    break;
                default:
                    boostFactor = 1;
                    break;
            }
            System.out.println("BoostFactor: " + boostFactor + " for " + field);
            qb.should(new MatchPhraseQueryBuilder(field, text).boost(boostFactor).slop(defaultSLOP));
        }
        searchSourceBuilder.query(qb).size(50);
        searchRequest.source(searchSourceBuilder);
        SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

        List<Class> classList = classExtract(searchResponse.toString());

        return classList;
    }

    public List<Class> queryString(String index, String text) throws IOException {
        SearchRequest searchRequest = new SearchRequest(index);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        QueryStringQueryBuilder qb = QueryBuilders.queryStringQuery(text);

        searchSourceBuilder.query(qb);
        searchRequest.source(searchSourceBuilder);
        SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

        System.out.println(searchResponse.toString());
        return  classExtract(searchResponse.toString());

    }

    private List<Class> classExtract(String json){
        List<Class> result = new ArrayList<>();
        JsonObject jsonObject = new JsonObject(json);
        JsonArray classes = jsonObject.getJsonObject("hits").getJsonArray("hits");

        classes.forEach(cl -> {
           JsonObject source = ((JsonObject) cl).getJsonObject("_source");

           System.out.println(source.toString());
//           System.out.println(source.getString("Subject"));

           result.add(Class.of(
                   source.getString("Subject"),
                   source.getString("Catalog"),
                   source.getString("Title"),
                   source.getString("Description"),
                   source.getString("Credit"),
                   Arrays.asList(source.getString("Term").split(" "))
           ));
        });

        return result;
    }

    private Map<String, List<Meeting>> meetingExtract(JsonArray termArray){
        Objects.requireNonNull(termArray, "termArray is null");

        Map<String, List<Meeting>> meetingMap = new HashMap<>();

        for (Object object: termArray){
            JsonObject meeting = (JsonObject) object;

            meetingMap.putIfAbsent(meeting.getString("Term"), new ArrayList<>());

            meetingMap.get(meeting.getString("Term"))
                      .add(Meeting.of(
                    meeting.getString("Term"),
                    meeting.getString("Code"),
                    meeting.getString("Room"),
                    Integer.parseInt(meeting.getString("number")),
                    meeting.getString("DayTime"),
                    mapper(meeting.getJsonObject("Instructor"))
            ));
        }

        return meetingMap;
    }

    private Map<String, String> mapper(JsonObject jsonObject) {
        Map<String, String> map = new HashMap<>();
        map.put("name", jsonObject.getString("name"));
        map.put("quality", jsonObject.getString("quality"));
        map.put("difficulty", jsonObject.getString("difficulty"));
        return map;
    }

    public static void main(String[] args) throws IOException {
        ElasticSearchAPI api = ElasticSearchAPI.makeConnection();

        System.out.println("connected");
        List<Class> ans = api.boolSearch(defaultINDEX, "acct101");
//        List<Class> ans = api.queryString("fall2019", "eecs");
        System.out.println(ans);

        api.closeConnection();

    }


}
