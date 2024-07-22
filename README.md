public class Result{
    public String scan_state;
    public String verdict;
    public ArrayList<Object> errors;
    public double duration;
    public double duration_full;
}

public class Root{
    public String name;
    public Object status;
    public String file;
    public String message;
    public String checkMessage;
    public int checkStatus;
    public ArrayList<Object> errors;
    public Date date;
    public Result result;
    public String fileUri;
} 
