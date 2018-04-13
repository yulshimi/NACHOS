package nachos.threads;
import java.util.*;
import nachos.machine.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm 
{
	PriorityQueue<thread_package> threadQueue = new PriorityQueue<thread_package>();
	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() 
	{
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() 
	{
		Boolean going_on = true;
		while(going_on)
		{
			going_on = false;
			if(threadQueue.size() > 0 && threadQueue.peek().wake_time <= Machine.timer().getTime())
			{
				thread_package popped_thread = threadQueue.poll();
				Machine.interrupt().disable();
				popped_thread.wake_thread.ready();
				Machine.interrupt().enable();
				going_on = true;
			}
		}
		KThread.currentThread().yield();
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) 
	{
		if(x > 0)
		{
			Machine.interrupt().disable();
			long wakeTime = Machine.timer().getTime() + x;
			KThread curr_thread = KThread.currentThread();
			thread_package myPackage = new thread_package();
			myPackage.set_wake_thread(curr_thread);
			myPackage.set_wake_time(wakeTime);
			threadQueue.offer(myPackage);
			KThread.sleep();
		}
	}

private class thread_package implements Comparable<thread_package>
{
	long wake_time;
	KThread wake_thread;
	public void set_wake_time(long time)
	{
		wake_time = time;
	}
	public void set_wake_thread(KThread input_thread)
	{
		wake_thread = input_thread;
	}
	public int compareTo(thread_package my_thread)
	{
		if(this.wake_time > my_thread.wake_time)
		{
			return 1;
		}
		else if(this.wake_time < my_thread.wake_time)
		{
			return -1;
		}
		else
		{
			return 0;
		}
	}
}

public static void alarmTest1() 
{
		int durations[] = {1000, 10*1000, 100*1000};
		long t0, t1;

		for (int d : durations) {
		    t0 = Machine.timer().getTime();
		    ThreadedKernel.alarm.waitUntil (d);
		    t1 = Machine.timer().getTime();
		    System.out.println ("alarmTest1: waited for " + (t1 - t0) + " ticks");
		}
	    }

	    // Implement more test methods here ...

	    // Invoke Alarm.selfTest() from ThreadedKernel.selfTest()
	    public static void selfTest() {
		alarmTest1();

		// Invoke your other test methods here ...
	    }
}
