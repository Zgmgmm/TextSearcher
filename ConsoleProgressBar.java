package TextSearcher;

import java.text.DecimalFormat;

public class ConsoleProgressBar {

    private long minimum = 0; // 进度条起始值   
    private long maximum = 100; // 进度条最大值  
    private long barLen = 100; // 进度条长度  
    private char showChar = '='; // 用于进度条显示的字符  
	private long lastValue=0; //上次显示的进度
    private DecimalFormat formater = new DecimalFormat("#.##%");
    
    public ConsoleProgressBar(long minimum, long maximum, 
            long barLen) {
        this.minimum = minimum;
        this.maximum = maximum;
        this.barLen = barLen;
    }
    
    public  synchronized void  update(long value,String msg) {
        
		cleanLine();
		if(msg!=null)
			System.out.println(msg);
		
        minimum = value;
        float rate = (float) (minimum*1.0 / maximum);
        long len = (long) (rate * barLen);
        draw(len, rate);
		lastValue=value;
    }
	public void update(long value){
		if(value==lastValue)
			return;
		update(value,null);
	}
	public void update(String msg){
		update(lastValue,msg);
	}
	private void cleanLine(){
		reset();
		for (int i = 0; i < barLen+5; i++) {
            System.out.print(' ');
        }
		reset();
	}
	
    private void draw(long len, float rate) {
        for (int i = 0; i < len; i++) {
            System.out.print(showChar);
        }
        System.out.print(' ');
        System.out.print(format(rate));
    }
  
    private void reset() {
        System.out.print('\r');
    }
    
    public void complete() {
		cleanLine();
    }

    private String format(float num) {
        return formater.format(num);
    }
    
}
