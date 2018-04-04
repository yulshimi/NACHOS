package nachos.vm;

import java.util.LinkedList;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel 
{
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() 
	{
		super();
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) 
	{
		super.initialize(args);
		m_swap_file = VMKernel.fileSystem.open("mySwap", true);
		m_inverted_table = new infoCenter[Machine.processor().getNumPhysPages()];
		for(int i=0; i < Machine.processor().getNumPhysPages(); ++i)
		{
			m_inverted_table[i] = new infoCenter();
		}
		m_page_fault_lock = new Lock();
		m_lock = new Lock();
		m_swap_lock = new Lock();
		m_condition = new Condition(m_lock);
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() 
	{
		super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run() 
	{
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() 
	{
		super.terminate();
	}
	
	public static int clockAlgorithm()
	{
		while(m_inverted_table[victim].getRefBit() == true || m_inverted_table[victim].pinned == true)
		{
			if(m_inverted_table[victim].pinned == false)
			{
				m_inverted_table[victim].clearRefBit();
			}
			victim = (victim + 1) % m_inverted_table.length;
		}
		int toEvict = victim;
		victim = (victim + 1) % m_inverted_table.length;
		if(m_inverted_table[toEvict].process.getDirtyBit(m_inverted_table[toEvict].vpn))
		{
			int swap_page = writeToSwap(toEvict);
			m_inverted_table[toEvict].process.setVpn(m_inverted_table[toEvict].vpn, swap_page);
		}
		m_inverted_table[toEvict].process.youAreEvicted(m_inverted_table[toEvict].vpn);
		return toEvict;
	}
	
	public static int writeToSwap(int p_page_no)
	{
		int p_address = p_page_no*pageSize;
		int swap_page = -1;
		m_swap_lock.acquire();
		if(m_free_swap_list.size() > 0)
		{
			swap_page = m_free_swap_list.removeFirst();
		}
		else
		{
			swap_page = numOfSwap;
			++numOfSwap;
		}
		m_swap_lock.release();
		m_swap_file.write(swap_page*pageSize, memory, p_address, pageSize);
		return swap_page;
	}
	public static Lock m_page_fault_lock;
	public static Lock m_swap_lock;
	public static Condition m_condition;
	public static Lock m_lock;
	public static int victim = 0;
	public static int numOfPinned = 0;
	public static byte[] memory = Machine.processor().getMemory();
	public static OpenFile m_swap_file;
	public static infoCenter[] m_inverted_table;
	public static LinkedList<Integer> m_free_swap_list = new LinkedList<Integer>();
	private static int numOfSwap = 0;
	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;
	private static final char dbgVM = 'v';
}
