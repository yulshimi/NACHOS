package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.io.EOFException;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess 
{
	/**
	 * Allocate a new process.
	 */
	public UserProcess() 
	{
		//int numPhysPages = Machine.processor().getNumPhysPages();
		m_pid = m_process_id;
		m_process_lock.acquire();
		++m_process_id;
		m_process_lock.release();
		m_num_process_lock.acquire();
		++m_num_of_process;
		m_num_process_lock.release();
		m_file_table = new OpenFile[16];
		m_file_table[0] = UserKernel.console.openForReading();
		m_file_table[1] = UserKernel.console.openForWriting();
		m_file_storage_lock.acquire();
		if(m_file_storage.get(m_file_table[0].getName()) == null)
		{
			fileStorage file_storage = new fileStorage(m_file_table[0]);
			m_file_storage.put(m_file_table[0].getName(), file_storage);
		}
		else
		{
			m_file_storage.get(m_file_table[0].getName()).reference();
		}
		
		if(m_file_storage.get(m_file_table[1].getName()) == null)
		{
			fileStorage file_storage = new fileStorage(m_file_table[1]);
			m_file_storage.put(m_file_table[1].getName(), file_storage);
		}
		else
		{
			m_file_storage.get(m_file_table[1].getName()).reference();
		}
		m_file_storage_lock.release();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() 
	{
	        String name = Machine.getProcessClassName ();

		// If Lib.constructObject is used, it quickly runs out
		// of file descriptors and throws an exception in
		// createClassLoader.  Hack around it by hard-coding
		// creating new processes of the appropriate type.

		if (name.equals ("nachos.userprog.UserProcess")) 
		{
		    return new UserProcess ();
		} 
		else if (name.equals ("nachos.vm.VMProcess")) 
		{
		    return new VMProcess ();
		} 
		else 
		{
		    return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
		}
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) 
	{
		if (!load(name, args))
			return false;

		new UThread(this).setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() 
	{
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() 
	{
		Machine.processor().setPageTable(pageTable);
	}
	
	public int addressTranslater(int in_v_address)
	{
		int v_page_no = in_v_address / pageSize;
		int v_offset = in_v_address % pageSize;
		if(v_page_no >= pageTable.length)
		{
			return -1;
		}
		if(pageTable[v_page_no] == null || pageTable[v_page_no].valid != true)
		{
			return -1;
		}
		pageTable[v_page_no].used = true;
		return pageTable[v_page_no].ppn*pageSize + v_offset;
	}
	
	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) 
	{
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) 
		{
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) 
	{
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) 
	{
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		int v_page_no = vaddr / pageSize;
		if(v_page_no >= numPages || v_page_no < 0)
		{
			return 0;
		}
		if(pageTable[v_page_no].valid == false)
		{
			return 0;
		}
		int v_offset = vaddr % pageSize;
		int possible_size = pageSize - v_offset + (numPages - 1 -v_page_no)*pageSize;
		int actual_length = Math.min(data.length-offset, Math.min(length, possible_size));
		int readBytes = 0;
		int progress_offset = v_offset;
		int index = offset;
		int p_address = pageTable[v_page_no].ppn * pageSize + v_offset;
		if(p_address < 0 || p_address >= memory.length)
		{
			return 0;
		}
		while(readBytes < actual_length)
		{
			data[index] = memory[p_address];
			++p_address;
			++progress_offset;
			++readBytes;
			++index;
			if(pageSize == progress_offset)
			{
				++v_page_no;
				if(v_page_no >= numPages)
				{
					break;
				}
				if(pageTable[v_page_no].valid == false)
				{
					break;
				}
				p_address = pageTable[v_page_no].ppn * pageSize;
				progress_offset = 0;
				if(readBytes < actual_length)
				{
					pageTable[v_page_no].used = true;
				}
			}
		}

		return readBytes;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) 
	{
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) 
	{
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		int v_page_no = vaddr / pageSize;
		if(v_page_no >= numPages || v_page_no < 0)
		{
			return 0;
		}
		if(pageTable[v_page_no].valid == false || pageTable[v_page_no].readOnly == true)
		{
			return 0;
		}
		int v_offset = vaddr % pageSize;
		int possible_size = pageSize - v_offset + (numPages - 1 - v_page_no)*pageSize;
		int actual_length = Math.min(data.length-offset, Math.min(length, possible_size));
		int writtenBytes = 0;
		
		int progress_offset = v_offset;
		int index = offset;
		int p_address = pageTable[v_page_no].ppn * pageSize + v_offset;
		if(p_address < 0 || p_address >= memory.length)
		{
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
				++v_page_no;
				if(v_page_no >= numPages)
				{
					break;
				}
				if(pageTable[v_page_no].valid == false || pageTable[v_page_no].readOnly == true)
				{
					break;
				}
				p_address = pageTable[v_page_no].ppn * pageSize;
				progress_offset = 0;
				if(writtenBytes < actual_length)
				{
					pageTable[v_page_no].dirty = true;
					pageTable[v_page_no].used = true;
				}
			}
		}
		return writtenBytes;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) 
	{
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) 
		{
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try 
		{
			coff = new Coff(executable);
		}
		catch (EOFException e) 
		{
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) 
		{
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) 
			{
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) 
		{
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) 
		{
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();
		m_page_needed = numPages;
		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) 
		{
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() 
	{
		m_page_list_lock.acquire();
		if(numPages > UserKernel.m_page_list.size())
		{
			m_page_list_lock.release();
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}
		pageTable = new TranslationEntry[numPages];
		for(int i=0; i < numPages; ++i)
		{
			int p_page_no = UserKernel.m_page_list.removeFirst();
			pageTable[i] = new TranslationEntry(i, p_page_no, true, false, false, false);
		}
		m_page_list_lock.release();
		
		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) 
		{
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) 
			{
				int vpn = section.getFirstVPN() + i;
				TranslationEntry trans_entry = pageTable[vpn];
				trans_entry.valid = true;
				trans_entry.readOnly = section.isReadOnly();
				section.loadPage(i, trans_entry.ppn);
			}
		}
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() 
	{
		for (int i = 0; i < pageTable.length; i++) 
		{
		    if (pageTable[i].valid == true) 
		    {
		    		m_page_list_lock.acquire();
		    		pageTable[i].valid = false; 
		    		UserKernel.m_page_list.add(pageTable[i].ppn);
		    		m_page_list_lock.release();
		    }
		}
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() 
	{
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}
	
	private boolean validFileDescriptor(int in_file_descriptor)
	{
		if(in_file_descriptor < 0 || in_file_descriptor >= m_file_table.length)
		{
			return false;
		}
		OpenFile lo_test_file = m_file_table[in_file_descriptor];
		return lo_test_file != null;
	}
	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() 
	{

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	private int handleJoin(int in_pid, int status_vaddr)
	{
		if(validAddress(status_vaddr) == false)
		{
			return -1;
		}
		childProcess child_process = m_children_list.get(in_pid);
		if(child_process == null) // pid wrong
		{
			return -1;
		}
		if(child_process.m_exit_val != null)
		{
			writeVirtualMemory(status_vaddr, Lib.bytesFromInt(child_process.m_exit_val));
			return 1;
		}
		if(child_process.m_exit_val == null)
		{
			child_process.m_process.m_join_lock.acquire();
			child_process.m_process.m_condition.sleep();
			child_process.m_process.m_join_lock.release();
		}
		m_children_list.remove(in_pid);
		if(child_process.m_exit_val == -1) //unhandled exception
		{
			writeVirtualMemory(status_vaddr, Lib.bytesFromInt(child_process.m_exit_val));
			return 0;
		}
		writeVirtualMemory(status_vaddr, Lib.bytesFromInt(child_process.m_exit_val));
		return 1;
	}
	
	private int handleExit(Integer in_status)
	{
		Machine.autoGrader().finishingCurrentProcess(in_status);
		m_join_lock.acquire();
		if(m_parent_process != null)
		{
			childProcess my_child = m_parent_process.m_children_list.get(m_pid);
			my_child.m_exit_val = in_status;
			//my_child.m_process = null;
		}
		for(childProcess my_child : m_children_list.values())
		{
			if(my_child.m_process != null)
			{
				my_child.m_process.m_parent_process = null;
			}
		}
		m_children_list = null;
		for(int i=0; i < m_file_table.length; ++i)
		{
			if(m_file_table[i] != null)
			{
				handleClose(i);
			}
		}
		unloadSections();
		m_exited = true;
		m_condition.wakeAll();
		m_join_lock.release();
		m_num_process_lock.acquire();
		--m_num_of_process;
		if(m_num_of_process == 0)
		{
			Kernel.kernel.terminate();
		}
		m_num_process_lock.release();
		UThread.finish();
		return in_status;
	}
	
	private int handleCreate(int in_vaddr)
	{
		if(validAddress(in_vaddr) == false)
		{
			return -1;
		}
		String name = readVirtualMemoryString(in_vaddr, m_max_length);
		if(name == null)
		{
			return -1;
		}
		OpenFile open_file;
		m_file_storage_lock.acquire();
		if(m_file_storage.get(name) == null) // if it does not exist
		{
			open_file = UserKernel.fileSystem.open(name, true);
			if(open_file == null)
			{
				m_file_storage_lock.release();
				return -1;
			}
			fileStorage new_one = new fileStorage(open_file);
			m_file_storage.put(name, new_one);
		}
		else // if it already exists
		{
			if(m_file_storage.get(name).unlinked() == true)
			{
				m_file_storage_lock.release();
				return -1; // unable to open it because it is unlinked
			}
			open_file = m_file_storage.get(name).getOpenFile();
			for(int i=0; i < m_file_table.length; ++i) // check if this process already refers it
			{
				if(m_file_table[i] == open_file)
				{
					m_file_storage_lock.release();
					return i; // immediately return a file descriptor
				}
			}
		}
		m_file_storage_lock.release();
		int file_descriptor = -1;
		for(int i = 2; i < m_file_table.length; ++i)
		{
			if(m_file_table[i] == null)
			{
				m_file_table[i] = open_file;
				file_descriptor = i;
				m_file_storage_lock.acquire();
				m_file_storage.get(name).reference();
				m_file_storage_lock.release();
				break;
			}
		}
		return file_descriptor;
	}
	
	private int handleOpen(int in_vaddr)
	{
		if(validAddress(in_vaddr) == false)
		{
			return -1;
		}
		String name = readVirtualMemoryString(in_vaddr, m_max_length);
		if(name == null)
		{
			return -1;
		}
		OpenFile open_file;
		m_file_storage_lock.acquire();
		if(m_file_storage.get(name) == null) // if it does not exist
		{
			m_file_storage_lock.release();
			return -1;
		}
		else // if it already exists
		{
			if(m_file_storage.get(name).unlinked() == true)
			{
				m_file_storage_lock.release();
				return -1; // unable to open it because it is unlinked
			}
			open_file = m_file_storage.get(name).getOpenFile();
			for(int i=0; i < m_file_table.length; ++i) // check if this process already refers it
			{
				if(m_file_table[i] == open_file)
				{
					m_file_storage_lock.release();
					return i; // immediately return a file descriptor
				}
			}
		}
		m_file_storage_lock.release();
		int file_descriptor = -1;
		for(int i = 2; i < m_file_table.length; ++i)
		{
			if(m_file_table[i] == null)
			{
				m_file_table[i] = open_file;
				file_descriptor = i;
				m_file_storage_lock.acquire();
				m_file_storage.get(name).reference();
				m_file_storage_lock.release();
				break;
			}
		}
		return file_descriptor;
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
		UserProcess child_process = new UserProcess(); // modified
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
	
	private int handleClose(int input_descriptor)
	{
		if(m_file_table[input_descriptor] != null)
		{
			m_file_storage_lock.acquire();
			fileStorage temp_storage = m_file_storage.get(m_file_table[input_descriptor].getName());
			temp_storage.dereference();
			String file_name = m_file_table[input_descriptor].getName();
			//m_file_table[input_descriptor].close();
			if(temp_storage.getNumOfUsage() == 0 && temp_storage.unlinked() == true)
			{
				m_file_table[input_descriptor].close();
				m_file_storage.remove(file_name);
				m_file_table[input_descriptor].getFileSystem().remove(file_name);
			}
			m_file_storage_lock.release();
			m_file_table[input_descriptor] = null;
			return input_descriptor;
		}
		return -1;
	}
	
	private int handleRead(int in_file_descriptor, int in_v_addr, int in_count)
	{
		if(validAddress(in_v_addr) == false || in_count < 0)
		{
			return -1;
		}
		if(validFileDescriptor(in_file_descriptor) == false)
		{
			return -1;
		}
		OpenFile opened_file = m_file_table[in_file_descriptor];
		if(opened_file == null)
		{
			return -1;
		}
		byte[] read_buffer = new byte[in_count];
		opened_file.seek(0);
		int read_bytes = opened_file.read(read_buffer, 0, in_count);
		if(read_bytes == -1)
		{
			return -1;
		}
		int written_bytes = writeVirtualMemory(in_v_addr, read_buffer);
		if(read_bytes != written_bytes)
		{
			return -1;
		}
		return written_bytes;
	}
	
	private int handleWrite(int in_file_descriptor, int in_v_addr, int in_count)
	{
		if(validAddress(in_v_addr) == false || in_count < 0)
		{
			return -1;
		}
		if(validFileDescriptor(in_file_descriptor) == false)
		{
			return -1;
		}
		OpenFile opened_file = m_file_table[in_file_descriptor];
		if(opened_file == null)
		{
			return -1;
		}
		byte write_buffer[] = new byte[in_count];
		int read_bytes = readVirtualMemory(in_v_addr, write_buffer, 0, in_count);
		int written_bytes = m_file_table[in_file_descriptor].write(write_buffer, 0, read_bytes);
		if(written_bytes < in_count)
		{
			return -1;
		}
		return written_bytes;
	}
	
	private int handleUnlink(int in_vaddr)
	{
		if(validAddress(in_vaddr) == false)
		{
			return -1;
		}
		String file_name = readVirtualMemoryString(in_vaddr, m_max_length);
		if(file_name == null)
		{
			return -1;
		}
		boolean is_closed = true;
		int file_descriptor = -1;
		for(int i=0; i < m_file_table.length; ++i)
		{
			if(m_file_table[i] != null)
			{
				if(m_file_table[i].getName().equals(file_name))
				{
					is_closed = false;
					file_descriptor = i;
					break;
				}
			}
		}
		if(is_closed == false)
		{	
			m_file_storage_lock.acquire();
			m_file_storage.get(file_name).setUnlinked();
			m_file_storage_lock.release();
			handleClose(file_descriptor);
			if(m_file_storage.get(file_name) == null)
			{
				return 0;
			}
		}
		else
		{
			m_file_storage_lock.acquire();
			if(m_file_storage.get(file_name) == null)
			{
				m_file_storage_lock.release();
				return -1; //unlink unlink error
			}
			m_file_storage.get(file_name).setUnlinked();
			if(m_file_storage.get(file_name).getNumOfUsage() == 0)
			{
				OpenFile temp_open_file = m_file_storage.get(file_name).getOpenFile();
				m_file_storage.remove(file_name);
				temp_open_file.getFileSystem().remove(file_name);
				m_file_storage_lock.release();
				return 0;
			}
			m_file_storage_lock.release();
		}
		return -1;
	}
	
	protected boolean validAddress(int in_vaddr)
	{
		int v_pgn = in_vaddr / pageSize;
		if(v_pgn < 0 || v_pgn >= numPages)
		{
			return false;
		}
		return true;
	}
	
	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) 
	{
		switch (syscall) 
		{
			case syscallHalt:
				return handleHalt();
			case syscallExit:
				return handleExit(a0);
			case syscallExec:
				return handleExec(a0, a1, a2);
			case syscallJoin:
				return handleJoin(a0, a1);
			case syscallCreate:
				return handleCreate(a0);
			case syscallOpen:
				return handleOpen(a0);
			case syscallRead:
				return handleRead(a0, a1, a2);
			case syscallWrite:
				return handleWrite(a0, a1, a2);
			case syscallClose:
				return handleClose(a0);
			case syscallUnlink:
				return handleUnlink(a0);
			default:
				Lib.debug(dbgProcess, "Unknown syscall " + syscall);
				Lib.assertNotReached("Unknown system call!");
		}
		return 0;
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
			case Processor.exceptionSyscall:
				int result = handleSyscall(processor.readRegister(Processor.regV0),
						processor.readRegister(Processor.regA0),
						processor.readRegister(Processor.regA1),
						processor.readRegister(Processor.regA2),
						processor.readRegister(Processor.regA3));
				processor.writeRegister(Processor.regV0, result);
				processor.advancePC();
				break;

			default:
				handleExit(-1);
			//Lib.debug(dbgProcess, "Unexpected exception: "
			//		+ Processor.exceptionNames[cause]);
			//Lib.assertNotReached("Unexpected exception");
		}
	}
	public int getPid()
	{
		return m_pid;
	}
	public UserProcess getParentProcess()
	{
		return m_parent_process;
	}
	public void setParentProcess(UserProcess in_process)
	{
		m_parent_process = in_process;
	}
	public boolean getExitStatus()
	{
		return m_exited;
	}
	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';
	protected int m_pid;
	protected int m_page_needed;
	private OpenFile[] m_file_table;
	private UserProcess m_parent_process = null;
	protected final int m_max_length = 256;
	private boolean m_exited = false;
	private Lock m_join_lock = new Lock();
	private Condition m_condition = new Condition(m_join_lock);
	protected HashMap<Integer, childProcess> m_children_list = new HashMap<Integer, childProcess>();
	
	public static Lock m_num_process_lock = new Lock();
	public static int m_num_of_process = 0;
	public static Lock m_process_lock = new Lock();
	public static Lock m_file_storage_lock = new Lock();
	public static int m_process_id = 0;
	public static HashMap<String, fileStorage> m_file_storage = new HashMap<String, fileStorage>();
	
	public static Lock m_page_list_lock = new Lock();
	//public static LinkedList<Integer> m_page_list = new LinkedList<Integer>();
}
