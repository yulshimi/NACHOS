package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess 
{
	/**
	 * Allocate a new process.
	 */
	public VMProcess() 
	{
		super();
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() 
	{
		super.saveState();
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() 
	{
		super.restoreState();
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() 
	{
		pageTable = new TranslationEntry[numPages];
		for(int i=0; i < numPages; ++i)
		{
			pageTable[i] = new TranslationEntry(i, -1, false, false, false, false);
		}
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() 
	{
		super.unloadSections();
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) 
	{
		Processor processor = Machine.processor();

		switch (cause) 
		{
			case Processor.exceptionPageFault:
				VMKernel.m_lock.acquire();
				handlePageFault(processor.readRegister(Processor.regBadVAddr));
				VMKernel.m_lock.release();
				break;
			default:
				super.handleException(cause);
				break;
		}
	}
	
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) 
	{
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		int v_page_no = vaddr / pageSize;
		if(v_page_no >= numPages || v_page_no < 0)
		{
			return 0;
		}
		if(pageTable[v_page_no].readOnly == true)
		{
			return 0;
		}
		
		VMKernel.m_lock.acquire();
		if(pageTable[v_page_no].valid == false)
		{
			handlePageFault(vaddr);
		}
		if(VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned == false)
		{
			VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = true;
			++VMKernel.numOfPinned;
		}
		VMKernel.m_lock.release();
		
		int v_offset = vaddr % pageSize;
		int possible_size = pageSize - v_offset + (numPages - 1 - v_page_no)*pageSize;
		int actual_length = Math.min(data.length-offset, Math.min(length, possible_size));
		int writtenBytes = 0;
		
		int progress_offset = v_offset;
		int index = offset;
		int p_address = pageTable[v_page_no].ppn * pageSize + v_offset;
		if(p_address < 0 || p_address >= memory.length) // remove it if it is not needed
		{
			VMKernel.m_lock.acquire();
			VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = false;
			--VMKernel.numOfPinned;
			VMKernel.m_condition.wake();
			VMKernel.m_lock.release();
			return 0;
		}
		pageTable[v_page_no].dirty = true;
		pageTable[v_page_no].used = true;
		while(writtenBytes < actual_length)
		{
			memory[p_address] = data[index];
			++p_address;
			++progress_offset;
			++writtenBytes;
			++index;
			if(pageSize == progress_offset)
			{
				VMKernel.m_lock.acquire();
				VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = false;
				--VMKernel.numOfPinned;
				VMKernel.m_condition.wake();
				VMKernel.m_lock.release();
				if(writtenBytes < actual_length)
				{
					++v_page_no;
					if(v_page_no >= numPages || pageTable[v_page_no].readOnly == true)
					{
						return writtenBytes;
					}
					
					VMKernel.m_lock.acquire();
					if(pageTable[v_page_no].valid == false)
					{
						handlePageFault(v_page_no*pageSize);
					}
					if(VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned == false)
					{
						VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = true;
						++VMKernel.numOfPinned;
					}
					VMKernel.m_lock.release();
					
					p_address = pageTable[v_page_no].ppn * pageSize;
					progress_offset = 0;
					pageTable[v_page_no].dirty = true;
					pageTable[v_page_no].used = true;
				}
			}
		}
		VMKernel.m_lock.acquire();
		VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = false;
		--VMKernel.numOfPinned;
		VMKernel.m_condition.wake();
		VMKernel.m_lock.release();
		return writtenBytes;
	}
	
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) 
	{
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		int v_page_no = vaddr / pageSize;
		if(v_page_no >= numPages || v_page_no < 0)
		{
			return 0;
		}
		
		VMKernel.m_lock.acquire();
		if(pageTable[v_page_no].valid == false)
		{
			handlePageFault(vaddr);
		}
		if(VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned == false)
		{
			VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = true;
			++VMKernel.numOfPinned;
		}
		VMKernel.m_lock.release();
		
		int v_offset = vaddr % pageSize;
		int possible_size = pageSize - v_offset + (numPages - 1 -v_page_no)*pageSize;
		int actual_length = Math.min(data.length-offset, Math.min(length, possible_size));
		int readBytes = 0;
		int progress_offset = v_offset;
		int index = offset;
		int p_address = pageTable[v_page_no].ppn * pageSize + v_offset;
		if(p_address < 0 || p_address >= memory.length)
		{
			VMKernel.m_lock.acquire();
			VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = false;
			--VMKernel.numOfPinned;
			VMKernel.m_condition.wake();
			VMKernel.m_lock.release();
			return 0;
		}
		pageTable[v_page_no].used = true;
		while(readBytes < actual_length)
		{
			data[index] = memory[p_address];
			++p_address;
			++progress_offset;
			++readBytes;
			++index;
			if(pageSize == progress_offset)
			{
				VMKernel.m_lock.acquire();
				VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = false;
				--VMKernel.numOfPinned;
				VMKernel.m_condition.wake();
				VMKernel.m_lock.release();
				if(readBytes < actual_length)
				{
					++v_page_no;
					if(v_page_no >= numPages)
					{
						return readBytes;
					}
					
					VMKernel.m_lock.acquire();
					if(pageTable[v_page_no].valid == false)
					{
						handlePageFault(v_page_no*pageSize);
					}
					if(VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned == false)
					{
						VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = true;
						++VMKernel.numOfPinned;
					}
					VMKernel.m_lock.release();
					
					p_address = pageTable[v_page_no].ppn * pageSize;
					progress_offset = 0;
					pageTable[v_page_no].used = true;
				}
			}
		}
		VMKernel.m_lock.acquire();
		VMKernel.m_inverted_table[pageTable[v_page_no].ppn].pinned = false;
		--VMKernel.numOfPinned;
		VMKernel.m_condition.wake();
		VMKernel.m_lock.release();
		return readBytes;
	}
	
	public void handlePageFault(int vaddr)
	{
		int v_page_no = vaddr / pageSize;
		if(v_page_no >= pageTable.length || v_page_no < 0)
		{
			return;
		}
		int p_page_no;
		if(0 < VMKernel.m_page_list.size())
		{
			p_page_no = VMKernel.m_page_list.removeFirst();
		}
		else
		{
			if(VMKernel.numOfPinned == Machine.processor().getNumPhysPages())
			{
				VMKernel.m_condition.sleep();
			}
			p_page_no = VMKernel.clockAlgorithm();
		}
		boolean isStack = true;
		if(VMKernel.m_inverted_table[p_page_no].pinned == false)
		{
			VMKernel.m_inverted_table[p_page_no].pinned = true;
			++VMKernel.numOfPinned;
		}
		VMKernel.m_inverted_table[p_page_no].process = this; 
		VMKernel.m_inverted_table[p_page_no].vpn = v_page_no;
		if(pageTable[v_page_no].ppn == -1) // the first page fault
		{
			for (int s = 0; s < coff.getNumSections(); s++) 
			{
				CoffSection section = coff.getSection(s);

				Lib.debug(dbgProcess, "\tinitializing " + section.getName() + " section (" + section.getLength() + " pages)");

				for (int i = 0; i < section.getLength(); i++) 
				{
					int vpn = section.getFirstVPN() + i;
					if(vpn == v_page_no)
					{
						TranslationEntry trans_entry = pageTable[vpn];
						trans_entry.readOnly = section.isReadOnly();
						section.loadPage(i, p_page_no);
						isStack = false;
					}
				}
			}
			if(isStack == true) // zero fill
			{
				int p_address = p_page_no*pageSize;
				byte[] memory = Machine.processor().getMemory();
				for(int i=0; i < pageSize; ++i)
				{
					memory[p_address] = 0;
					++p_address;
				}
			}
		}
		else
		{
			if(pageTable[v_page_no].readOnly == true)
			{
				for (int s = 0; s < coff.getNumSections(); s++) 
				{
					CoffSection section = coff.getSection(s);

					Lib.debug(dbgProcess, "\tinitializing " + section.getName() + " section (" + section.getLength() + " pages)");

					for (int i = 0; i < section.getLength(); i++) 
					{
						int vpn = section.getFirstVPN() + i;
						if(vpn == v_page_no)
						{
							section.loadPage(i, p_page_no);
						}
					}
				}
			}
			else
			{
				if(pageTable[v_page_no].dirty == true)
				{
					VMKernel.m_swap_file.read(pageTable[v_page_no].vpn*pageSize, VMKernel.memory, p_page_no*pageSize, pageSize);
					VMKernel.m_swap_lock.acquire();
					VMKernel.m_free_swap_list.add(pageTable[v_page_no].vpn);
					VMKernel.m_swap_lock.release();
				}
				else //readOnly == false and dirty bit == false
				{
					for (int s = 0; s < coff.getNumSections(); s++) 
					{
						CoffSection section = coff.getSection(s);

						Lib.debug(dbgProcess, "\tinitializing " + section.getName() + " section (" + section.getLength() + " pages)");

						for (int i = 0; i < section.getLength(); i++) 
						{
							int vpn = section.getFirstVPN() + i;
							if(vpn == v_page_no)
							{
								section.loadPage(i, p_page_no);
								isStack = false;
							}
						}
					}
					if(isStack == true) // zero fill
					{
						int p_address = p_page_no*pageSize;
						byte[] memory = Machine.processor().getMemory();
						for(int i=0; i < pageSize; ++i)
						{
							memory[p_address] = 0;
							++p_address;
						}
					}
				}
			}
		}
		pageTable[v_page_no].vpn = v_page_no;
		pageTable[v_page_no].valid = true;
		pageTable[v_page_no].ppn = p_page_no;
		VMKernel.m_inverted_table[p_page_no].pinned = false;
		--VMKernel.numOfPinned;
		VMKernel.m_condition.wake();
	}
	
	protected int handleExec(int file_vaddr, int argc, int arg_vaddr)
	{
		if(validAddress(file_vaddr) == false || validAddress(arg_vaddr) == false)
		{
			return -1;
		}
		String name = readVirtualMemoryString(file_vaddr, m_max_length);
		if(name == null || argc < 0)
		{
			return -1;
		}
		String file_extension = name.substring(name.length()-5);
		if(file_extension.equals(".coff") == false)
		{
			return -1;
		}
		String argument_list[] = new String[argc]; 
		for(int i=0; i < argc; ++i)
		{
			byte arg_addr[] = new byte[4];
			readVirtualMemory(arg_vaddr+i*4, arg_addr);
			int vaddr = Lib.bytesToInt(arg_addr,0);
			if(validAddress(vaddr) == false)
			{
				return -1;
			}
			argument_list[i] = readVirtualMemoryString(vaddr, m_max_length);
		}
		UserProcess child_process = new VMProcess(); // modified
		childProcess new_born = new childProcess(child_process);
		m_children_list.put(child_process.getPid(), new_born);
		child_process.setParentProcess(this);
		Boolean result = child_process.execute(name, argument_list);
		if(result == false)
		{
			return -1;
		}
		return child_process.getPid();
	}
	
	public void youAreEvicted(int vpn)
	{
		pageTable[vpn].valid = false;
	}
	
	public boolean getDirtyBit(int index)
	{
		return pageTable[index].dirty;
	}
	
	public void setVpn(int index, int swap)
	{
		pageTable[index].vpn = swap;
	}
	
	public boolean getUsedBit(int index)
	{
		return pageTable[index].used;
	}
	
	public void setUsedBit(int index, boolean bool)
	{
		pageTable[index].used = bool;
	}
	
	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
